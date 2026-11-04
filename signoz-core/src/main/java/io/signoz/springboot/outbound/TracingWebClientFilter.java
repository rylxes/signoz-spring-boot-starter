package io.signoz.springboot.outbound;

import io.opentelemetry.api.trace.Span;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.tracing.TraceIdentity;
import io.signoz.springboot.webflux.ReactiveMdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * A reactive {@link ExchangeFilterFunction} that injects W3C {@code traceparent} headers
 * into outbound {@link org.springframework.web.reactive.function.client.WebClient} calls
 * and logs request/response information.
 *
 * <p>Header propagation uses the format:
 * {@code 00-{traceId}-{spanId}-{traceFlags}}.
 *
 * <p>The logged URL has its sensitive query parameters masked and any user info removed (see
 * {@link OutboundUrl}), so {@code ?access_token=abc} does not reach the log in clear.
 */
public class TracingWebClientFilter implements ExchangeFilterFunction {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_OUTBOUND");

    /**
     * How the response status is read, resolved once. This module compiles against Spring 5,
     * where {@code ClientResponse.rawStatusCode()} exists, but Spring 6.1 removed that method,
     * so calling it directly is a {@link NoSuchMethodError} on Spring Boot 3.2+. And
     * {@code statusCode()} returns {@code HttpStatus} on Spring 5 but {@code HttpStatusCode} on
     * Spring 6, so a direct call to it fails on Spring 6 too.
     *
     * <p>Where {@code rawStatusCode()} exists it is used, because Spring 5's {@code statusCode()}
     * throws for a non-standard code such as 499. Otherwise {@code statusCode()} and then
     * {@code value()} on its declared return type are used.
     */
    private static final Method RAW_STATUS_CODE;
    private static final Method STATUS_CODE;
    private static final Method STATUS_VALUE;

    static {
        Method rawStatusCode = null;
        Method statusCode = null;
        Method statusValue = null;
        try {
            rawStatusCode = ClientResponse.class.getMethod("rawStatusCode");
        } catch (NoSuchMethodException | RuntimeException notOnThisSpringVersion) {
            try {
                statusCode = ClientResponse.class.getMethod("statusCode");
                statusValue = statusCode.getReturnType().getMethod("value");
            } catch (NoSuchMethodException | RuntimeException ignored) {
                // The status is logged as -1 rather than failing the exchange.
                statusCode = null;
            }
        }
        RAW_STATUS_CODE = rawStatusCode;
        STATUS_CODE = statusCode;
        STATUS_VALUE = statusValue;
    }

    private final SigNozOutboundProperties properties;
    private final MaskingRegistry maskingRegistry;

    /**
     * Creates a new filter that removes user info from logged URLs but does not mask their query
     * strings. Prefer {@link #TracingWebClientFilter(SigNozOutboundProperties, MaskingRegistry)}.
     *
     * @param properties outbound tracing configuration
     */
    public TracingWebClientFilter(SigNozOutboundProperties properties) {
        this(properties, null);
    }

    /**
     * Creates a new filter.
     *
     * @param properties      outbound tracing configuration
     * @param maskingRegistry masks sensitive query parameters in logged URLs; may be {@code null}
     */
    public TracingWebClientFilter(SigNozOutboundProperties properties, MaskingRegistry maskingRegistry) {
        this.properties = properties;
        this.maskingRegistry = maskingRegistry;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(contextView -> {
            ClientRequest.Builder requestBuilder = ClientRequest.from(request);

            if (properties.isPropagateHeaders()) {
                Map<String, String> reactiveMdc = ReactiveMdc.fromContext(contextView);
                String traceparent = buildTraceparent(reactiveMdc);
                if (traceparent != null) {
                    requestBuilder.header("traceparent", traceparent);
                }
                // Also forward X-Request-ID. The Reactor context (populated by
                // TraceIdWebFilter) is reliable across thread hops; the thread MDC
                // covers servlet and other blocking callers.
                String requestId = reactiveMdc.get(TraceIdentity.MDC_REQUEST_ID);
                if (requestId == null || requestId.isEmpty()) {
                    requestId = MDC.get(TraceIdentity.MDC_REQUEST_ID);
                }
                if (requestId != null && !requestId.isEmpty()) {
                    requestBuilder.header("X-Request-ID", requestId);
                }
            }

            ClientRequest modifiedRequest = requestBuilder.build();
            long start = System.currentTimeMillis();

            return next.exchange(modifiedRequest)
                    .doOnSuccess(response -> {
                        if (properties.isLogRequests()) {
                            long duration = System.currentTimeMillis() - start;
                            logger.info("[SigNoz] Outbound {} {} -> {} in {}ms",
                                    modifiedRequest.method(),
                                    OutboundUrl.forLog(modifiedRequest.url(), maskingRegistry),
                                    statusCode(response),
                                    duration);
                        }
                    })
                    .doOnError(ex -> {
                        if (properties.isLogRequests()) {
                            long duration = System.currentTimeMillis() - start;
                            String url = OutboundUrl.forLog(modifiedRequest.url(), maskingRegistry);
                            logger.warn("[SigNoz] Outbound {} {} -> FAILED in {}ms: {}",
                                    modifiedRequest.method(),
                                    url,
                                    duration,
                                    OutboundUrl.maskMessage(ex.getMessage(), modifiedRequest.url(), url));
                        }
                    });
        });
    }

    /**
     * Returns the response's HTTP status code on Spring 5 and Spring 6, or {@code -1} when it
     * cannot be read. Never throws, so logging cannot fail the exchange.
     *
     * @param response the response; {@code null} when the exchange completed empty
     * @return the status code, or {@code -1}
     */
    static int statusCode(ClientResponse response) {
        if (response == null) {
            return -1;
        }
        try {
            if (RAW_STATUS_CODE != null) {
                return (Integer) RAW_STATUS_CODE.invoke(response);
            }
            if (STATUS_CODE != null && STATUS_VALUE != null) {
                return (Integer) STATUS_VALUE.invoke(STATUS_CODE.invoke(response));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through: the status is logged as -1.
        }
        return -1;
    }

    /**
     * Build a W3C {@code traceparent} for the outbound request, preferring in order:
     * the active OTel span; the request's entries in the Reactor context (set by
     * {@code TraceIdWebFilter}, so correct even after thread hops); the thread MDC.
     *
     * @return the formatted {@code traceparent} header value, or {@code null} when
     *         no identity is available to propagate
     */
    private static String buildTraceparent(Map<String, String> reactiveMdc) {
        if (Span.current().getSpanContext().isValid()) {
            return TraceContextCodec.currentTraceparent();
        }
        String traceId = reactiveMdc.get(TraceContextCodec.MDC_TRACE_ID);
        String spanId = reactiveMdc.get(TraceContextCodec.MDC_SPAN_ID);
        if (traceId != null && !traceId.isEmpty() && spanId != null && !spanId.isEmpty()) {
            String flags = reactiveMdc.get(TraceContextCodec.MDC_TRACE_FLAGS);
            return TraceContextCodec.format(traceId, spanId,
                    flags == null || flags.isEmpty() ? "01" : flags);
        }
        return TraceContextCodec.mdcTraceparent();
    }
}
