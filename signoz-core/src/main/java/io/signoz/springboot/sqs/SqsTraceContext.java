package io.signoz.springboot.sqs;

import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Static helpers for SQS trace propagation. Used by the SDK v1 request handler
 * and the {@code @SqsListener} aspect, and exposed for users on AWS SDK v2 or
 * other listener frameworks who want to inject/extract trace context manually.
 *
 * <p>Wire format: a single message attribute named {@code traceparent} carrying
 * the W3C value {@code 00-{traceId}-{spanId}-{flags}} as a String.
 *
 * <p>Why message attributes (not the body): SQS lets attributes ride alongside
 * the payload without consumers needing to deserialize anything to read them.
 */
public final class SqsTraceContext {

    /** Message attribute name carrying the W3C traceparent. */
    public static final String TRACEPARENT_ATTRIBUTE = "traceparent";

    /** Companion attribute carrying any inbound X-Request-ID for non-W3C callers. */
    public static final String REQUEST_ID_ATTRIBUTE = "X-Request-ID";

    private SqsTraceContext() {
        // utility class
    }

    /**
     * Read a W3C traceparent value from an arbitrary header / attribute map and
     * populate the SLF4J {@link MDC} so downstream logs share the upstream
     * trace ID. Caller is responsible for clearing MDC after the unit of work.
     *
     * @param headers map keyed by header name (case-sensitive). Values may be
     *                {@code String} or types whose {@code toString()} yields the value.
     * @return {@code true} when MDC was populated, {@code false} when no valid
     *         traceparent was found.
     */
    public static boolean populateMdcFromHeaders(Map<String, ?> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        Object raw = headers.get(TRACEPARENT_ATTRIBUTE);
        if (raw == null) {
            raw = headers.get("traceparent");
        }
        TraceContextCodec.Parsed parsed = raw == null
                ? null
                : TraceContextCodec.parse(raw.toString());
        if (parsed == null) {
            return false;
        }
        MDC.put("traceId", parsed.traceId);
        MDC.put("spanId", parsed.spanId);
        MDC.put("traceFlags", parsed.traceFlags);

        Object requestId = headers.get(REQUEST_ID_ATTRIBUTE);
        if (requestId == null) {
            requestId = headers.get("requestId");
        }
        if (requestId != null) {
            MDC.put("requestId", requestId.toString());
        } else {
            MDC.put("requestId", parsed.traceId);
        }
        return true;
    }

    /** Clear the MDC keys populated by {@link #populateMdcFromHeaders(Map)}. */
    public static void clearMdc() {
        MDC.remove("traceId");
        MDC.remove("spanId");
        MDC.remove("traceFlags");
        MDC.remove("requestId");
    }
}
