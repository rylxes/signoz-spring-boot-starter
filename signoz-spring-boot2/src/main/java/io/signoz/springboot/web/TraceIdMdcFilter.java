package io.signoz.springboot.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
import java.util.UUID;

/**
 * Servlet filter that extracts the active OpenTelemetry trace/span IDs and
 * injects them into the SLF4J MDC for the duration of each HTTP request.
 *
 * <p>This ensures every log statement written during the request automatically
 * includes {@code traceId} and {@code spanId}, enabling end-to-end correlation
 * between traces and logs in SigNoz.
 *
 * <p>Spring Boot 2.x / {@code javax.servlet} version.
 *
 * <p>MDC fields populated:
 * <ul>
 *   <li>{@code traceId} — 32-character hex string</li>
 *   <li>{@code spanId} — 16-character hex string</li>
 *   <li>{@code traceFlags} — W3C trace flags (e.g. {@code "01"} for sampled)</li>
 *   <li>{@code requestId} — random UUID per request (useful when tracing is off)</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("requestId", requestId);

        // Set X-Request-ID response header for client-side correlation
        response.setHeader("X-Request-ID", requestId);

        Span currentSpan = Span.current();
        if (currentSpan != null) {
            SpanContext ctx = currentSpan.getSpanContext();
            if (ctx.isValid()) {
                MDC.put("traceId", ctx.getTraceId());
                MDC.put("spanId", ctx.getSpanId());
                MDC.put("traceFlags", ctx.getTraceFlags().asHex());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
            MDC.remove("traceFlags");
            MDC.remove("requestId");
        }
    }
}
