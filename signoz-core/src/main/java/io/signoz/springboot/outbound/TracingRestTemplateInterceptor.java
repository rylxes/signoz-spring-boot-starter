package io.signoz.springboot.outbound;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * A {@link ClientHttpRequestInterceptor} that injects W3C {@code traceparent} headers
 * into outbound {@link org.springframework.web.client.RestTemplate} calls and logs
 * request/response information.
 *
 * <p>Header propagation uses the format:
 * {@code 00-{traceId}-{spanId}-{traceFlags}}.
 */
public class TracingRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_OUTBOUND");

    private final SigNozOutboundProperties properties;

    /**
     * Creates a new interceptor.
     *
     * @param properties outbound tracing configuration
     */
    public TracingRestTemplateInterceptor(SigNozOutboundProperties properties) {
        this.properties = properties;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (properties.isPropagateHeaders()) {
            String traceparent = buildTraceparent();
            if (traceparent != null) {
                request.getHeaders().set("traceparent", traceparent);
            }
            // Also forward X-Request-ID so downstream services that don't speak W3C
            // traceparent still get correlation. MDC is populated by TraceIdMdcFilter.
            String requestId = MDC.get("requestId");
            if (requestId != null && !requestId.isEmpty()) {
                request.getHeaders().set("X-Request-ID", requestId);
            }
        }

        long start = System.currentTimeMillis();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long duration = System.currentTimeMillis() - start;

            if (properties.isLogRequests()) {
                logger.info("[SigNoz] Outbound {} {} -> {} in {}ms",
                        request.getMethod(),
                        request.getURI(),
                        response.getRawStatusCode(),
                        duration);
            }
            return response;
        } catch (IOException ex) {
            long duration = System.currentTimeMillis() - start;
            if (properties.isLogRequests()) {
                logger.warn("[SigNoz] Outbound {} {} -> FAILED in {}ms: {}",
                        request.getMethod(),
                        request.getURI(),
                        duration,
                        ex.getMessage());
            }
            throw ex;
        }
    }

    /**
     * Build a W3C {@code traceparent} for the outbound request. Prefers the active
     * OTEL {@link SpanContext} when available (OTEL agent or SDK path); otherwise
     * falls back to MDC values populated by {@code TraceIdMdcFilter} so header
     * propagation still works in fully agentless deployments.
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
