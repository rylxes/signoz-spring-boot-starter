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
 * <p>Sensitive query parameters in the logged URL are masked with the {@link MaskingRegistry}.
 */
public class TracingWebClientFilter implements ExchangeFilterFunction {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_OUTBOUND");

    /**
     * {@code ClientResponse.statusCode()} returns {@code HttpStatus} on Spring 5 and
     * {@code HttpStatusCode} on Spring 6, so a direct call compiled against one is a
     * {@link NoSuchMethodError} on the other, and {@code rawStatusCode()} is gone from
     * Spring 6.1. Both return types expose a public {@code value()}, so resolve the pair once.
     */
    private static final Method STATUS_CODE;
    private static final Method STATUS_VALUE;

    static {
        Method statusCode = null;
        Method statusValue = null;
        try {
            statusCode = ClientResponse.class.getMethod("statusCode");
            statusValue = statusCode.getReturnType().getMethod("value");
        } catch (NoSuchMethodException | RuntimeException ignored) {
            // Status is logged as -1 rather than failing the exchange.
        }
        STATUS_CODE = statusCode;
        STATUS_VALUE = statusValue;
    }

    private final SigNozOutboundProperties properties;
    private final MaskingRegistry maskingRegistry;

    /**
     * Creates a new filter that masks logged query strings with the default masking rules.
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
     * @param maskingRegistry masks sensitive query parameters in the logged URL; {@code null}
     *                        falls back to the default masking rules
     */
    public TracingWebClientFilter(SigNozOutboundProperties properties, MaskingRegistry maskingRegistry) {
        this.properties = properties;
        this.maskingRegistry = OutboundUriRenderer.orDefault(maskingRegistry);
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
                                    OutboundUriRenderer.render(modifiedRequest.url(), maskingRegistry),
                                    statusCode(response),
                                    duration);
                        }
                    })
                    .doOnError(ex -> {
                        if (properties.isLogRequests()) {
                            long duration = System.currentTimeMillis() - start;
                            logger.warn("[SigNoz] Outbound {} {} -> FAILED in {}ms: {}",
                                    modifiedRequest.method(),
                                    OutboundUriRenderer.render(modifiedRequest.url(), maskingRegistry),
                                    duration,
                                    ex.getMessage());
                        }
                    });
        });
    }

    /**
     * Returns the response's HTTP status code on both Spring 5 and Spring 6, or {@code -1} when
     * it cannot be read. Never throws, so logging cannot fail the exchange.
     */
    static int statusCode(ClientResponse response) {
        if (STATUS_CODE == null || STATUS_VALUE == null) {
            return -1;
        }
        try {
            return (Integer) STATUS_VALUE.invoke(STATUS_CODE.invoke(response));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
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
