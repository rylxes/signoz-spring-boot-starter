package io.signoz.springboot.outbound;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            SpanContext spanContext = Span.current().getSpanContext();
            if (spanContext.isValid()) {
                String traceparent = String.format("00-%s-%s-%s",
                        spanContext.getTraceId(),
                        spanContext.getSpanId(),
                        spanContext.getTraceFlags().asHex());
                request.getHeaders().set("traceparent", traceparent);
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
}
