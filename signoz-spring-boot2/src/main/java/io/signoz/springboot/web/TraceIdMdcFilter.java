package io.signoz.springboot.web;

import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.tracing.TraceIdentity;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * Servlet filter that populates MDC with trace-correlation IDs for every HTTP
 * request, choosing the identity in this priority order:
 *
 * <ol>
 *   <li><b>Active OTEL span</b> — when the OpenTelemetry Java Agent (or SDK) is
 *       present, the agent's {@code traceId}/{@code spanId}/{@code traceFlags}
 *       are used. This is the normal path when you run with
 *       {@code -javaagent:opentelemetry-javaagent.jar}.</li>
 *   <li><b>Inbound W3C {@code traceparent} header</b> — when no agent is active
 *       but an upstream caller forwarded a valid {@code traceparent}, its
 *       {@code traceId} is reused so all logs across the hop share an ID. A
 *       fresh 16-hex {@code spanId} is minted for this hop so downstream
 *       services treat us as their parent.</li>
 *   <li><b>Inbound {@code X-Request-ID} header</b> — agentless fallback when
 *       the caller doesn't know about {@code traceparent}. The value is
 *       normalised to 32 hex chars for W3C compatibility.</li>
 *   <li><b>Freshly minted IDs</b> — when we're the edge of the system.</li>
 * </ol>
 *
 * <p>Regardless of path, the filter writes the W3C {@code traceparent} and
 * {@code X-Request-ID} headers on the response so clients and downstream
 * services can see and propagate the identity.
 *
 * <p>MDC keys populated (cleared in {@code finally}): {@code requestId} always,
 * plus OpenTelemetry-native {@code trace_id}/{@code span_id}/{@code trace_flags}
 * (snake_case) ONLY when no OTel agent is active. When the agent has populated a
 * valid span it already emits those keys, so this filter defers to it and does not
 * write duplicate copies (the legacy camelCase keys are no longer emitted).
 *
 * <p>Spring Boot 2.x / {@code javax.servlet} version.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Same resolution as the reactive TraceIdWebFilter, shared via TraceIdentity.
        TraceIdentity identity = TraceIdentity.resolve(
                request.getHeader(TraceContextCodec.TRACEPARENT_HEADER),
                request.getHeader(TraceIdentity.REQUEST_ID_HEADER));
        Map<String, String> mdc = identity.mdcEntries();

        for (Map.Entry<String, String> e : mdc.entrySet()) {
            MDC.put(e.getKey(), e.getValue());
        }
        response.setHeader(TraceIdentity.REQUEST_ID_HEADER, identity.getRequestId());
        response.setHeader(TraceContextCodec.TRACEPARENT_HEADER, identity.toTraceparent());

        try {
            filterChain.doFilter(request, response);
        } finally {
            for (String key : mdc.keySet()) {
                MDC.remove(key);
            }
        }
    }
}
