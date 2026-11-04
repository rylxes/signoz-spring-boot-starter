package io.signoz.springboot.logging.log4j2;

import io.signoz.springboot.errors.ErrorFingerprint;
import io.signoz.springboot.logging.OtlpLogSupport;
import io.signoz.springboot.masking.MaskingRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.status.StatusLogger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Log4j2 counterpart of the Logback {@code SigNozJsonEncoder}: one JSON object per line,
 * with the same field names the Logstash encoder produces, so SigNoz log pipelines and
 * queries work the same whichever backend the application uses.
 *
 * <pre>{"@timestamp":"…","level":"INFO","logger_name":"…","thread_name":"…","message":"…",
 *  "trace_id":"…", …context data…, "errorId":"…", "stack_trace":"…"}</pre>
 *
 * <p>Thread-context (MDC) entries are written as top-level fields, like the Logstash
 * encoder; entries whose names collide with the standard fields are skipped. When error
 * fingerprinting is enabled, ERROR events with a throwable carry {@code errorId}, computed
 * from the event itself rather than written to the thread context, so it cannot linger
 * on the thread and tag later log lines.
 *
 * <p>Masking is applied to each value <em>before</em> it is serialised: the message and stack
 * trace with {@link MaskingRegistry#maskJsonString(String)}, context entries by key (sensitive
 * keys with their field strategy, others with the message-level patterns). Masking the finished
 * line instead would miss a JSON payload inside the message, whose quotes are escaped
 * ({@code \"password\":\"…\"}) once embedded in the line. Masking fails closed: if it throws,
 * the message, context and stack trace are withheld rather than written unmasked.
 */
public class SigNozLog4j2JsonLayout extends AbstractStringLayout {

    static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "@timestamp", "level", "logger_name", "thread_name", "message", "errorId", "stack_trace"));

    private final MaskingRegistry maskingRegistry;
    private final int errorFingerprintDepth;

    /**
     * @param maskingRegistry       masks each value before it is serialised (message, context,
     *                              stack trace); content is withheld if masking throws;
     *                              {@code null} disables masking
     * @param errorFingerprintDepth stack frames used for {@code errorId}; {@code 0} disables it
     */
    public SigNozLog4j2JsonLayout(MaskingRegistry maskingRegistry, int errorFingerprintDepth) {
        super(StandardCharsets.UTF_8);
        this.maskingRegistry = maskingRegistry;
        this.errorFingerprintDepth = errorFingerprintDepth;
    }

    @Override
    public String getContentType() {
        return "application/json; charset=UTF-8";
    }

    @Override
    public String toSerializable(LogEvent event) {
        try {
            return render(event, true) + "\n";
        } catch (Exception e) {
            StatusLogger.getLogger().error("[SigNoz] Masking failed; withholding log content", e);
            return render(event, false) + "\n";
        }
    }

    private String render(LogEvent event, boolean includeContent) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "@timestamp", Instant.ofEpochMilli(event.getTimeMillis()).toString());
        sb.append(',');
        field(sb, "level", event.getLevel().name());
        sb.append(',');
        field(sb, "logger_name", event.getLoggerName());
        sb.append(',');
        field(sb, "thread_name", event.getThreadName());
        sb.append(',');
        if (!includeContent) {
            field(sb, "message", OtlpLogSupport.BODY_WITHHELD);
            return sb.append('}').toString();
        }
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        field(sb, "message", maskText(message));

        Map<String, String> context = event.getContextData() == null
                ? null : event.getContextData().toMap();
        if (context != null) {
            for (Map.Entry<String, String> e : context.entrySet()) {
                if (e.getValue() != null && !RESERVED.contains(e.getKey())) {
                    sb.append(',');
                    field(sb, e.getKey(), maskContext(e.getKey(), e.getValue()));
                }
            }
        }

        Throwable thrown = event.getThrown();
        if (thrown != null) {
            if (errorFingerprintDepth > 0 && Level.ERROR.equals(event.getLevel())) {
                sb.append(',');
                field(sb, "errorId", ErrorFingerprint.generate(thrown, errorFingerprintDepth));
            }
            StringWriter trace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            sb.append(',');
            field(sb, "stack_trace", maskText(trace.toString()));
        }
        return sb.append('}').toString();
    }

    private String maskText(String text) {
        return maskingRegistry == null ? text : maskingRegistry.maskJsonString(text);
    }

    private String maskContext(String key, String value) {
        if (maskingRegistry == null) {
            return value;
        }
        return maskingRegistry.isSensitiveField(key)
                ? maskingRegistry.mask(key, value)
                : maskingRegistry.maskMessage(value);
    }

    private static void field(StringBuilder sb, String name, String value) {
        quote(sb, name);
        sb.append(':');
        quote(sb, value == null ? "" : value);
    }

    /** JSON string escaping per RFC 8259. */
    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
