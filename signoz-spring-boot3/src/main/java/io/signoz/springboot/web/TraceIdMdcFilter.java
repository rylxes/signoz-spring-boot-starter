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
import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /** W3C traceparent: {@code version-traceId-parentSpanId-flags}, all lowercase hex. */
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final SecureRandom RNG = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String inboundRequestId = trimToNull(request.getHeader("X-Request-ID"));
        String inboundTraceparent = trimToNull(request.getHeader("traceparent"));

        String traceId;
        String spanId;
        String traceFlags;

        Span currentSpan = Span.current();
        SpanContext ctx = currentSpan != null ? currentSpan.getSpanContext() : null;
        boolean agentPopulated = ctx != null && ctx.isValid();

        if (agentPopulated) {
            // OTEL agent already emits trace_id/span_id/trace_flags into MDC;
            // reuse its identity for the response headers but don't duplicate the MDC keys.
            traceId = ctx.getTraceId();
            spanId = ctx.getSpanId();
            traceFlags = ctx.getTraceFlags().asHex();
        } else if (inboundTraceparent != null && TRACEPARENT.matcher(inboundTraceparent).matches()) {
            String[] parts = inboundTraceparent.split("-");
            traceId = parts[1];
            spanId = randomHex(16);
            traceFlags = parts[3];
        } else if (inboundRequestId != null) {
            traceId = normalizeToHex(inboundRequestId, 32);
            spanId = randomHex(16);
            traceFlags = "01";
        } else {
            traceId = UUID.randomUUID().toString().replace("-", "");
            spanId = randomHex(16);
            traceFlags = "01";
        }

        String requestId = inboundRequestId != null ? inboundRequestId : traceId;

        MDC.put("requestId", requestId);
        if (!agentPopulated) {
            // OpenTelemetry-native snake_case keys, only when no agent owns them,
            // so logs are consistent and queryable in SigNoz without duplicates.
            MDC.put("trace_id", traceId);
            MDC.put("span_id", spanId);
            MDC.put("trace_flags", traceFlags);
        }

        response.setHeader("X-Request-ID", requestId);
        response.setHeader("traceparent", "00-" + traceId + "-" + spanId + "-" + traceFlags);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            if (!agentPopulated) {
                MDC.remove("trace_id");
                MDC.remove("span_id");
                MDC.remove("trace_flags");
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[chars / 2];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(chars);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /**
     * Coerce an arbitrary inbound ID to a fixed-length lowercase-hex string so
     * it can fit a W3C {@code traceparent}. Hex characters are preserved; non-hex
     * input is hashed deterministically for stable cross-service mapping.
     */
    private static String normalizeToHex(String value, int length) {
        String stripped = value.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        if (stripped.length() >= length) {
            return stripped.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(stripped);
        int seed = value.hashCode();
        while (sb.length() < length) {
            sb.append(String.format("%08x", seed));
            seed = seed * 31 + 17;
        }
        return sb.substring(0, length);
    }
}
