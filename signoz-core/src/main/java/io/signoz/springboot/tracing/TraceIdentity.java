package io.signoz.springboot.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The trace identity of one inbound request, resolved the same way as the servlet
 * {@code TraceIdMdcFilter} so servlet and reactive services agree on IDs:
 *
 * <ol>
 *   <li><b>Active OTel span</b> (agent or SDK): its IDs are reused. The agent already
 *       owns the {@code trace_id}/{@code span_id}/{@code trace_flags} MDC keys, so
 *       {@link #mdcEntries()} then carries only {@code requestId}.</li>
 *   <li><b>Inbound W3C {@code traceparent}</b>: its trace ID is reused and a fresh
 *       span ID is minted for this hop.</li>
 *   <li><b>Inbound {@code X-Request-ID}</b>: coerced to a 32-hex trace ID.</li>
 *   <li><b>Nothing inbound</b>: fresh IDs, this service is the edge.</li>
 * </ol>
 */
public final class TraceIdentity {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final SecureRandom RNG = new SecureRandom();

    private final String traceId;
    private final String spanId;
    private final String traceFlags;
    private final String requestId;
    private final boolean agentPopulated;

    private TraceIdentity(String traceId, String spanId, String traceFlags,
                          String requestId, boolean agentPopulated) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.traceFlags = traceFlags;
        this.requestId = requestId;
        this.agentPopulated = agentPopulated;
    }

    /**
     * Resolve the identity for a request from its raw header values (either may be
     * {@code null}) and the current OTel span.
     */
    public static TraceIdentity resolve(String traceparentHeader, String requestIdHeader) {
        String inboundRequestId = trimToNull(requestIdHeader);
        SpanContext ctx = Span.current().getSpanContext();

        String traceId;
        String spanId;
        String traceFlags;
        boolean agentPopulated = ctx.isValid();

        TraceContextCodec.Parsed parsed;
        if (agentPopulated) {
            traceId = ctx.getTraceId();
            spanId = ctx.getSpanId();
            traceFlags = ctx.getTraceFlags().asHex();
        } else if ((parsed = TraceContextCodec.parse(traceparentHeader)) != null) {
            traceId = parsed.traceId;
            spanId = randomHex(16);
            traceFlags = parsed.traceFlags;
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
        return new TraceIdentity(traceId, spanId, traceFlags, requestId, agentPopulated);
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getTraceFlags() {
        return traceFlags;
    }

    public String getRequestId() {
        return requestId;
    }

    /** {@code true} when an active OTel span (agent or SDK) supplied the IDs. */
    public boolean isAgentPopulated() {
        return agentPopulated;
    }

    /** The {@code traceparent} to echo on the response and forward downstream. */
    public String toTraceparent() {
        return TraceContextCodec.format(traceId, spanId, traceFlags);
    }

    /**
     * The MDC entries this identity contributes: always {@code requestId}, plus the
     * OTel-native snake_case trace keys unless an agent already owns them.
     */
    public Map<String, String> mdcEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put(MDC_REQUEST_ID, requestId);
        if (!agentPopulated) {
            entries.put(TraceContextCodec.MDC_TRACE_ID, traceId);
            entries.put(TraceContextCodec.MDC_SPAN_ID, spanId);
            entries.put(TraceContextCodec.MDC_TRACE_FLAGS, traceFlags);
        }
        return Collections.unmodifiableMap(entries);
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
     * Coerce an arbitrary inbound ID to a fixed-length lowercase-hex string. Hex
     * characters are preserved; non-hex input is padded deterministically so the
     * same ID always maps to the same trace ID.
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
