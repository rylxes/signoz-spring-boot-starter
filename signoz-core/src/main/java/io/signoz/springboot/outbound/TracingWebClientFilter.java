package io.signoz.springboot.outbound;

import io.opentelemetry.api.trace.Span;
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

import java.util.Map;

/**
 * A reactive {@link ExchangeFilterFunction} that injects W3C {@code traceparent} headers
 * into outbound {@link org.springframework.web.reactive.function.client.WebClient} calls
 * and logs request/response information.
 *
 * <p>Header propagation uses the format:
 * {@code 00-{traceId}-{spanId}-{traceFlags}}.
 */
public class TracingWebClientFilter implements ExchangeFilterFunction {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_OUTBOUND");

    private final SigNozOutboundProperties properties;

    /**
     * Creates a new filter.
     *
     * @param properties outbound tracing configuration
     */
    public TracingWebClientFilter(SigNozOutboundProperties properties) {
        this.properties = properties;
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
                                    modifiedRequest.url(),
                                    response.rawStatusCode(),
                                    duration);
                        }
                    })
                    .doOnError(ex -> {
                        if (properties.isLogRequests()) {
                            long duration = System.currentTimeMillis() - start;
                            logger.warn("[SigNoz] Outbound {} {} -> FAILED in {}ms: {}",
                                    modifiedRequest.method(),
                                    modifiedRequest.url(),
                                    duration,
                                    ex.getMessage());
                        }
                    });
        });
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
