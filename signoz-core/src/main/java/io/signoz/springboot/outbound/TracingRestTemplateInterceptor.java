package io.signoz.springboot.outbound;

import io.signoz.springboot.compat.ResponseStatusReader;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;

/**
 * A {@link ClientHttpRequestInterceptor} that injects W3C {@code traceparent} headers
 * into outbound {@link org.springframework.web.client.RestTemplate} calls and logs
 * request/response information.
 *
 * <p>Header propagation uses the format:
 * {@code 00-{traceId}-{spanId}-{traceFlags}}.
 *
 * <p>The logged URL has its sensitive query parameters masked and any user info removed (see
 * {@link OutboundUrl}), so {@code ?access_token=abc} does not reach the log in clear.
 */
public class TracingRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_OUTBOUND");

    /**
     * Reads the status on Spring 5, 6 and 7: {@code ClientHttpResponse.getRawStatusCode()} was
     * removed in Spring 7.0. See {@link ResponseStatusReader}.
     */
    private static final ResponseStatusReader STATUS =
            new ResponseStatusReader(ClientHttpResponse.class, "getRawStatusCode", "getStatusCode");

    private final SigNozOutboundProperties properties;
    private final MaskingRegistry maskingRegistry;

    /**
     * Creates a new interceptor that masks logged query strings with the default masking rules,
     * ignoring {@code signoz.logging.*} customisations. Prefer
     * {@link #TracingRestTemplateInterceptor(SigNozOutboundProperties, MaskingRegistry)} with the
     * application's registry.
     *
     * @param properties outbound tracing configuration
     */
    public TracingRestTemplateInterceptor(SigNozOutboundProperties properties) {
        this(properties, null);
    }

    /**
     * Creates a new interceptor.
     *
     * @param properties      outbound tracing configuration
     * @param maskingRegistry masks sensitive query parameters in logged URLs; {@code null} uses
     *                        the default masking rules
     */
    public TracingRestTemplateInterceptor(SigNozOutboundProperties properties,
                                          MaskingRegistry maskingRegistry) {
        this.properties = properties;
        this.maskingRegistry = OutboundUrl.orDefault(maskingRegistry);
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
                        OutboundUrl.forLog(request.getURI(), maskingRegistry),
                        STATUS.read(response),
                        duration);
            }
            return response;
        } catch (IOException ex) {
            long duration = System.currentTimeMillis() - start;
            if (properties.isLogRequests()) {
                URI uri = request.getURI();
                String url = OutboundUrl.forLog(uri, maskingRegistry);
                logger.warn("[SigNoz] Outbound {} {} -> FAILED in {}ms: {}",
                        request.getMethod(),
                        url,
                        duration,
                        OutboundUrl.maskMessage(ex.getMessage(), uri, url));
            }
            throw ex;
        }
    }

    /**
     * Build a W3C {@code traceparent} for the outbound request. Prefers the active
     * OTEL {@code SpanContext} when available (OTEL agent or SDK path); otherwise
     * falls back to MDC values populated by {@code TraceIdMdcFilter} so header
     * propagation still works in fully agentless deployments.
     *
     * @return the formatted {@code traceparent} header value, or {@code null} when
     *         no identity is available to propagate
     */
    private static String buildTraceparent() {
        return TraceContextCodec.currentTraceparent();
    }
}
