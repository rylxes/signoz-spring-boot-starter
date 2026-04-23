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
 * <p>MDC keys populated (cleared in {@code finally}):
 * {@code traceId}, {@code spanId}, {@code traceFlags}, {@code requestId}.
 *
 * <p>Spring Boot 2.x / {@code javax.servlet} version.
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

        if (ctx != null && ctx.isValid()) {
            // OTEL agent (or SDK) populated a span — defer to it.
            traceId = ctx.getTraceId();
            spanId = ctx.getSpanId();
            traceFlags = ctx.getTraceFlags().asHex();
        } else if (inboundTraceparent != null && TRACEPARENT.matcher(inboundTraceparent).matches()) {
            // No active span, but upstream sent a W3C traceparent — honour it.
            String[] parts = inboundTraceparent.split("-");
            traceId = parts[1];
            spanId = randomHex(16); // fresh span for this hop
            traceFlags = parts[3];
        } else if (inboundRequestId != null) {
            // No traceparent either — reuse the inbound X-Request-ID as the traceId.
            traceId = normalizeToHex(inboundRequestId, 32);
            spanId = randomHex(16);
            traceFlags = "01";
        } else {
            // We're at the edge of the system. Mint fresh IDs.
            traceId = UUID.randomUUID().toString().replace("-", "");
            spanId = randomHex(16);
            traceFlags = "01";
        }

        // Preserve the caller's X-Request-ID verbatim when provided; otherwise use the traceId.
        String requestId = inboundRequestId != null ? inboundRequestId : traceId;

        MDC.put("requestId", requestId);
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);
        MDC.put("traceFlags", traceFlags);

        response.setHeader("X-Request-ID", requestId);
        response.setHeader("traceparent", "00-" + traceId + "-" + spanId + "-" + traceFlags);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
            MDC.remove("traceFlags");
            MDC.remove("requestId");
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
     * Coerce an arbitrary inbound ID to a fixed-length lowercase-hex string so it
     * can fit a W3C {@code traceparent}. Hex characters are preserved in place;
     * non-hex input is hashed deterministically so the same input maps stably to
     * the same traceId across services.
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
