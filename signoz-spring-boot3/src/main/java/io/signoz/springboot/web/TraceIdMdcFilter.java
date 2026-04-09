package io.signoz.springboot.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
import java.util.UUID;

/**
 * Servlet filter that extracts the active OpenTelemetry trace/span IDs and
 * injects them into the SLF4J MDC for the duration of each HTTP request.
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

        String requestId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);

        // Use real OTEL trace ID when agent is present, otherwise fall back to requestId
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            SpanContext ctx = currentSpan.getSpanContext();
            if (ctx.isValid()) {
                MDC.put("traceId", ctx.getTraceId());
                MDC.put("spanId", ctx.getSpanId());
                MDC.put("traceFlags", ctx.getTraceFlags().asHex());
            } else {
                MDC.put("traceId", requestId);
            }
        } else {
            MDC.put("traceId", requestId);
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
