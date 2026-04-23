package io.signoz.springboot.outbound;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

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
                String traceparent = buildTraceparent();
                if (traceparent != null) {
                    requestBuilder.header("traceparent", traceparent);
                }
                // Also forward X-Request-ID from MDC when available. Note: in pure
                // reactive chains MDC is usually empty due to thread-switching, so
                // this is best-effort. Blocking/servlet callers still benefit.
                String requestId = MDC.get("requestId");
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
     * Build a W3C {@code traceparent} for the outbound request. Prefers the active
     * OTEL {@link SpanContext} when available; otherwise falls back to MDC values
     * populated by {@code TraceIdMdcFilter} so agentless deployments still
     * propagate trace context (best-effort — MDC may be empty in reactive chains
     * that don't copy thread-locals into Reactor context).
     *
     * @return the formatted {@code traceparent} header value, or {@code null} when
     *         no identity is available to propagate
     */
    private static String buildTraceparent() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            return String.format("00-%s-%s-%s",
                    spanContext.getTraceId(),
                    spanContext.getSpanId(),
                    spanContext.getTraceFlags().asHex());
        }
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || traceId.isEmpty() || spanId == null || spanId.isEmpty()) {
            return null;
        }
        String flags = MDC.get("traceFlags");
        if (flags == null || flags.isEmpty()) {
            flags = "01";
        }
        return String.format("00-%s-%s-%s", traceId, spanId, flags);
    }
}
