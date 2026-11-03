package io.signoz.springboot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.signoz.springboot.masking.MaskingRegistry;
import net.logstash.logback.encoder.LogstashEncoder;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Custom Logback encoder that wraps {@link LogstashEncoder} (Logstash-compatible JSON)
 * and applies {@link MaskingRegistry} masking to every log record.
 *
 * <p>The masking pipeline:
 * <ol>
 *   <li>The parent {@code LogstashEncoder} serialises the event to a JSON line.</li>
 *   <li>The line is re-read token by token and every value is masked on its own, unescaped:
 *       <ul>
 *         <li>a value under a sensitive key (the message's own fields, MDC keys, structured
 *             arguments) goes through {@link MaskingRegistry#mask(String, String)};</li>
 *         <li>any other string value goes through {@link MaskingRegistry#maskJsonString(String)},
 *             so JSON, form bodies and free-text secrets inside the message, MDC values and stack
 *             traces are masked as the raw text they are.</li>
 *       </ul></li>
 *   <li>The masked tokens are written back out as the final line.</li>
 * </ol>
 *
 * <p>Masking each value separately, rather than running the patterns over the serialised line, is
 * what makes this correct. Over the line, JSON embedded in a message is escaped
 * ({@code \"password\":\"x\"}) so no {@code "field":"value"} pattern matches it, and free-text
 * patterns such as {@code password=(\S+)} run past the end of the string they matched in and
 * swallow the rest of the line.
 *
 * <p>Masking fails closed: if any step throws, the message is withheld and a minimal record
 * (timestamp, level, logger, {@link #MASKING_FAILED_MESSAGE}) is written instead of the raw event.
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

    /** Message written in place of the original when masking throws. */
    public static final String MASKING_FAILED_MESSAGE = "[signoz:masking-failed:message-withheld]";

    private static final String LINE_SEPARATOR = System.lineSeparator();

    /** Thread-safe once configured; shared by every encoder instance. */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

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
        MaskingRegistry registry = this.maskingRegistry;
        if (registry == null || event == null) {
            return super.encode(event);
        }

        try {
            byte[] bytes = super.encode(event);
            if (bytes == null) {
                return null;
            }
            String line = new String(bytes, StandardCharsets.UTF_8);
            return maskJsonLine(line, registry).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fail closed: a log line that may carry an unmasked secret is worse than a missing one,
            // so keep the fact that something was logged but not what.
            addError("[SigNoz] Masking failed; withholding log message", e);
            return withheldRecord(event);
        }
    }

    @Override
    public void encode(ILoggingEvent event, java.io.OutputStream outputStream) throws IOException {
        byte[] encoded = encode(event);
        if (encoded != null) {
            outputStream.write(encoded);
        }
    }

    // --- Per-value masking ---

    /**
     * Masks every value of a serialised JSON line and returns the re-serialised line, keeping
     * whatever trailing line separator the encoder appended.
     */
    static String maskJsonLine(String line, MaskingRegistry registry) throws IOException {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        String body = line.substring(0, end);
        String trailer = line.substring(end);

        StringWriter out = new StringWriter(line.length() + 16);
        JsonParser parser = JSON_FACTORY.createParser(body);
        JsonGenerator generator = JSON_FACTORY.createGenerator(out);
        try {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                // For a value inside an array this is the array's own field name, so a list under a
                // sensitive key is masked element by element.
                String field = parser.currentName();
                switch (token) {
                    case VALUE_STRING:
                        generator.writeString(maskValue(registry, field, parser.getText()));
                        break;
                    case VALUE_NUMBER_INT:
                    case VALUE_NUMBER_FLOAT:
                        if (registry.isSensitiveField(field)) {
                            // A card number or CVV logged as a number is still sensitive.
                            generator.writeString(registry.mask(field, parser.getText()));
                        } else {
                            // Written from the original text so no precision is lost on the way through.
                            generator.writeNumber(parser.getText());
                        }
                        break;
                    default:
                        generator.copyCurrentEvent(parser);
                        break;
                }
            }
        } finally {
            parser.close();
            generator.close();
        }
        return out.toString() + trailer;
    }

    private static String maskValue(MaskingRegistry registry, String field, String value) {
        if (registry.isSensitiveField(field)) {
            return registry.mask(field, value);
        }
        // maskJsonString is a superset of maskMessage: it applies the message patterns, then
        // "field":"value" and field=value pairs, so JSON and form bodies in a value are covered.
        return registry.maskJsonString(value);
    }

    // --- Fail-closed fallback ---

    /**
     * Hand-built rather than produced by LogstashEncoder, so it cannot fail for the same reason the
     * normal path did. Only fields that carry no user data are included.
     */
    private static byte[] withheldRecord(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"@timestamp\":\"").append(Instant.ofEpochMilli(event.getTimeStamp())).append('"');
        sb.append(",\"message\":\"").append(MASKING_FAILED_MESSAGE).append('"');
        appendStringField(sb, "logger_name", event.getLoggerName());
        if (event.getLevel() != null) {
            appendStringField(sb, "level", event.getLevel().toString());
            sb.append(",\"level_value\":").append(event.getLevel().toInt());
        }
        sb.append('}').append(LINE_SEPARATOR);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendStringField(StringBuilder sb, String name, String value) {
        if (value == null) {
            return;
        }
        sb.append(",\"").append(name).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}
