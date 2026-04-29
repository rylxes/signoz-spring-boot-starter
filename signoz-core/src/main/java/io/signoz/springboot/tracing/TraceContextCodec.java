package io.signoz.springboot.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared encode/decode helpers for the W3C {@code traceparent} header used by
 * every protocol module (HTTP, Kafka, SQS, gRPC, WebSocket).
 *
 * <p>Encode preference order:
 * <ol>
 *   <li>The active OTEL {@link SpanContext} (set by the agent or the starter SDK).</li>
 *   <li>MDC values populated by {@code TraceIdMdcFilter} for agentless deployments.</li>
 * </ol>
 *
 * <p>Decode parses a W3C {@code traceparent} of the form
 * {@code 00-{32-hex-traceId}-{16-hex-spanId}-{2-hex-flags}}.
 */
public final class TraceContextCodec {

    public static final String TRACEPARENT_HEADER = "traceparent";

    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private TraceContextCodec() {
        // utility class
    }

    /**
     * Build a W3C {@code traceparent} value for the current execution context, or
     * {@code null} when no identity is available to propagate.
     */
    public static String currentTraceparent() {
        SpanContext ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            return format(ctx.getTraceId(), ctx.getSpanId(), ctx.getTraceFlags().asHex());
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
        return format(traceId, spanId, flags);
    }

    /**
     * Parse a W3C {@code traceparent} header into its components, or return
     * {@code null} when the input is missing or malformed.
     */
    public static Parsed parse(String traceparent) {
        if (traceparent == null) return null;
        String trimmed = traceparent.trim().toLowerCase(Locale.ROOT);
        if (!TRACEPARENT.matcher(trimmed).matches()) {
            return null;
        }
        String[] parts = trimmed.split("-");
        return new Parsed(parts[1], parts[2], parts[3]);
    }

    /**
     * Format components into a W3C {@code traceparent} value (version {@code 00}).
     */
    public static String format(String traceId, String spanId, String flags) {
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }

    /** Parsed components of a W3C {@code traceparent}. */
    public static final class Parsed {
        public final String traceId;
        public final String spanId;
        public final String traceFlags;

        public Parsed(String traceId, String spanId, String traceFlags) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.traceFlags = traceFlags;
        }
    }
}
