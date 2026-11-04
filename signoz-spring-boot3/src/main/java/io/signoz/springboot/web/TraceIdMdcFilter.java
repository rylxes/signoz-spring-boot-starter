package io.signoz.springboot.web;

import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.tracing.TraceIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Servlet filter that populates MDC with trace-correlation IDs for every HTTP
 * request, choosing the identity in this priority order:
 *
 * <ol>
 *   <li><b>Active OTEL span</b> — when the OpenTelemetry Java Agent (or SDK) is
 *       present, the agent's {@code traceId}/{@code spanId}/{@code traceFlags}
 *       are used.</li>
 *   <li><b>Inbound W3C {@code traceparent} header</b> — agentless propagation
 *       from an upstream caller. Its {@code traceId} is reused; a fresh
 *       16-hex {@code spanId} is minted for this hop.</li>
 *   <li><b>Inbound {@code X-Request-ID} header</b> — agentless fallback for
 *       callers that don't speak W3C.</li>
 *   <li><b>Freshly minted IDs</b> — when we're the edge of the system.</li>
 * </ol>
 *
 * <p>Spring Boot 3.x / {@code jakarta.servlet} version.
 *
 * @see io.signoz.springboot.web.TraceIdMdcFilter (SB2 counterpart uses javax.servlet)
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
