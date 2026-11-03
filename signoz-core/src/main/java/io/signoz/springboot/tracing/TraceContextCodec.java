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
 *   <li>MDC values for agentless deployments. The OTel-native snake_case keys
 *       ({@code trace_id}, {@code span_id}, {@code trace_flags}) written by
 *       {@code TraceIdMdcFilter} are read first; the legacy camelCase keys
 *       ({@code traceId}, {@code spanId}, {@code traceFlags}) still written by the
 *       Kafka, SQS, gRPC and STOMP consumers are the fallback.</li>
 * </ol>
 *
 * <p>Decode parses a W3C {@code traceparent} of the form
 * {@code 00-{32-hex-traceId}-{16-hex-spanId}-{2-hex-flags}}.
 */
public final class TraceContextCodec {

    public static final String TRACEPARENT_HEADER = "traceparent";

    /** OTel-native MDC keys, written by {@code TraceIdMdcFilter} and the OTel agent. */
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_TRACE_FLAGS = "trace_flags";

    /** Legacy camelCase MDC keys, still written by the messaging/RPC consumers. */
    public static final String MDC_LEGACY_TRACE_ID = "traceId";
    public static final String MDC_LEGACY_SPAN_ID = "spanId";
    public static final String MDC_LEGACY_TRACE_FLAGS = "traceFlags";

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
        return mdcTraceparent();
    }

    /**
     * Build a {@code traceparent} from the current thread's MDC only, or {@code null}
     * when the MDC carries no trace identity. A trace/span pair is taken from one key
     * family (snake_case preferred) so the two halves can never come from different
     * writers.
     */
    public static String mdcTraceparent() {
        String traceId = MDC.get(MDC_TRACE_ID);
        String spanId = MDC.get(MDC_SPAN_ID);
        String flags = MDC.get(MDC_TRACE_FLAGS);
        if (isEmpty(traceId) || isEmpty(spanId)) {
            traceId = MDC.get(MDC_LEGACY_TRACE_ID);
            spanId = MDC.get(MDC_LEGACY_SPAN_ID);
            flags = MDC.get(MDC_LEGACY_TRACE_FLAGS);
        }
        if (isEmpty(traceId) || isEmpty(spanId)) {
            return null;
        }
        return format(traceId, spanId, isEmpty(flags) ? "01" : flags);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
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
