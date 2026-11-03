package io.signoz.springboot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.signoz.springboot.masking.MaskingRegistry;
import net.logstash.logback.encoder.LogstashEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Custom Logback encoder that wraps {@link LogstashEncoder} (Logstash-compatible JSON)
 * and applies {@link MaskingRegistry} masking to every log record before serialisation.
 *
 * <p>The masking pipeline:
 * <ol>
 *   <li>The event's formatted message and MDC are masked first (see {@link MaskedLoggingEvent}),
 *       because a JSON payload inside the message is escaped once embedded in the line and
 *       {@link MaskingRegistry#maskJsonString(String)} cannot match its fields afterwards.</li>
 *   <li>The parent {@code LogstashEncoder} serialises the masked event.</li>
 *   <li>The whole line is passed through {@code maskJsonString} for everything else
 *       (stack traces, arguments, custom providers).</li>
 * </ol>
 *
 * <p>If masking throws, a minimal line with the content withheld is written instead of the
 * unmasked line.
 *
 * <p>Configured in {@code logback-signoz.xml} (or programmatically):
 * <pre>{@code
 * <encoder class="io.signoz.springboot.logging.SigNozJsonEncoder">
 *     <includeContext>true</includeContext>
 *     <includeMdc>true</includeMdc>
 * </encoder>
 * }</pre>
 */
public class SigNozJsonEncoder extends LogstashEncoder {

    /** Message written instead of the event's content when masking throws. */
    static final String BODY_WITHHELD = "[signoz:masking-failed:body-withheld]";

    private MaskingRegistry maskingRegistry;

    public SigNozJsonEncoder() {
        // Exclude Spring Boot's logback context properties from JSON output
        // (CONSOLE_LOG_PATTERN, FILE_LOG_PATTERN, PID, etc.)
        setIncludeContext(false);
        // Exclude "@version":"1" — not useful for SigNoz
        setVersion(null);
    }

    /**
     * Called by the auto-configuration to inject the masking registry.
     * When used via XML, configure the registry via a Spring bean reference.
     */
    public void setMaskingRegistry(MaskingRegistry maskingRegistry) {
        this.maskingRegistry = maskingRegistry;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        if (maskingRegistry == null) {
            return super.encode(event);
        }
        try {
            // Mask the message and MDC before serialisation: once a JSON payload in the message
            // is embedded in the line, its quotes are escaped and field masking cannot see it.
            byte[] bytes = super.encode(MaskedLoggingEvent.wrap(event, maskingRegistry));
            if (bytes == null) {
                return null;
            }
            // Then the whole line, for everything else the encoder writes (stack traces,
            // arguments, custom providers).
            String masked = maskingRegistry.maskJsonString(new String(bytes, StandardCharsets.UTF_8));
            return masked.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fail closed: writing the unmasked line is the failure masking exists to prevent.
            addError("[SigNoz] Masking failed; withholding log content", e);
            return withheld(event);
        }
    }

    /** A minimal line that keeps the event visible without any of its content. */
    static byte[] withheld(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"@timestamp\":\"").append(Instant.ofEpochMilli(event.getTimeStamp()))
                .append("\",\"level\":\"").append(event.getLevel())
                .append("\",\"logger_name\":\"").append(jsonSafe(event.getLoggerName()))
                .append("\",\"message\":\"").append(BODY_WITHHELD).append("\"}")
                .append(System.lineSeparator());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonSafe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void encode(ILoggingEvent event, java.io.OutputStream outputStream) throws IOException {
        byte[] encoded = encode(event);
        if (encoded != null) {
            outputStream.write(encoded);
        }
    }
}
