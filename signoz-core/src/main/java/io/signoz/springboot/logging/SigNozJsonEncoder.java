package io.signoz.springboot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import io.signoz.springboot.masking.MaskingRegistry;
import net.logstash.logback.encoder.LogstashEncoder;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom Logback encoder that wraps {@link LogstashEncoder} (Logstash-compatible JSON)
 * and applies {@link MaskingRegistry} masking to every log record.
 *
 * <p>The masking pipeline:
 * <ol>
 *   <li>The parent {@code LogstashEncoder} serialises the event to a JSON line.</li>
 *   <li>The line is walked with a streaming JSON parser and every value is masked
 *       <em>unescaped</em>, one value at a time, rather than by running regexes over the raw line
 *       (see {@link #maskSerialisedLine(String, MaskingRegistry)}). This covers the message, MDC
 *       values, structured arguments, markers and stack traces in one pass.</li>
 *   <li>Only if the line is not a single JSON value (a custom prefix, for example) does the encoder
 *       fall back to regexes over the raw text. It then re-serialises the event with its message and
 *       MDC masked first ({@link #maskedView(ILoggingEvent, MaskingRegistry)}), because once
 *       {@code LogstashEncoder} has escaped JSON embedded in a message ({@code \"password\":\"x\"}) no
 *       {@code "field":"value"} pattern matches it any more.</li>
 * </ol>
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
            String masked = maskSerialisedLine(new String(bytes, StandardCharsets.UTF_8), registry);
            if (masked == null) {
                // Not plain JSON, so the regexes have to run over the raw line. Re-serialise with the
                // message and MDC masked first, while their embedded JSON is still unescaped.
                bytes = super.encode(maskedView(event, registry));
                if (bytes == null) {
                    return null;
                }
                masked = registry.maskJsonString(new String(bytes, StandardCharsets.UTF_8));
            }
            return masked.getBytes(StandardCharsets.UTF_8);
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

    // --- Post-serialisation masking ---

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /**
     * Masks a line LogstashEncoder has already serialised, without letting a pattern cross a JSON
     * string boundary.
     *
     * <p>Running {@link MaskingRegistry#maskJsonString(String)} over the raw line is unsafe because
     * its free-text patterns assume whitespace-delimited text. LogstashEncoder writes no whitespace
     * between fields, so {@code password=\S+} at the end of the {@code message} value ran on through
     * the closing quote and swallowed every field after it, leaving invalid JSON. The same {@code \S+}
     * also ate escape sequences ({@code \"}, {@code \n}) inside values such as stack traces.
     *
     * <p>Instead the line is copied token by token. Each string value is masked as plain text, which
     * also lets the {@code "field":"value"} patterns see JSON embedded in a value with its quotes
     * unescaped. A scalar under a field registered as sensitive (for example a structured argument
     * {@code kv("password", ...)}) is masked with that field's strategy, including every element of
     * a list or nested object under it. Field names, structure and number literals are written back
     * unchanged.
     *
     * @return the masked line, or {@code null} if the line is not a single JSON value (a custom
     *         prefix, for example), in which case the caller has to mask the raw text instead
     */
    static String maskSerialisedLine(String line, MaskingRegistry registry) throws IOException {
        StringWriter out = new StringWriter(line.length() + 16);
        int consumed;
        try {
            JsonParser parser = JSON_FACTORY.createParser(line);
            JsonGenerator generator = JSON_FACTORY.createGenerator(out);
            try {
                if (parser.nextToken() == null) {
                    return null;
                }
                copyMasked(parser, generator, registry);
                generator.flush();
                consumed = (int) parser.getCurrentLocation().getCharOffset();
            } finally {
                parser.close();
                generator.close();
            }
        } catch (JsonProcessingException notJson) {
            return null;
        }
        if (consumed < 0 || consumed > line.length()) {
            return null;
        }
        // Whatever follows the JSON value: normally just the line separator, but a configured
        // suffix is free text, so it gets the free-text treatment.
        String tail = line.substring(consumed);
        out.write(tail.trim().isEmpty() ? tail : registry.maskJsonString(tail));
        return out.toString();
    }

    /** Copies the value at the parser's current token, and everything nested in it, masking scalars. */
    private static void copyMasked(JsonParser parser, JsonGenerator generator, MaskingRegistry registry)
            throws IOException {
        int depth = 0;
        do {
            JsonToken token = parser.getCurrentToken();
            switch (token) {
                case START_OBJECT:
                case START_ARRAY:
                    depth++;
                    generator.copyCurrentEvent(parser);
                    break;
                case END_OBJECT:
                case END_ARRAY:
                    depth--;
                    generator.copyCurrentEvent(parser);
                    break;
                case VALUE_STRING: {
                    String field = sensitiveFieldName(parser, registry);
                    String text = parser.getText();
                    generator.writeString(field != null
                            ? registry.mask(field, text)
                            : registry.maskJsonString(text));
                    break;
                }
                case VALUE_NUMBER_INT:
                case VALUE_NUMBER_FLOAT: {
                    String field = sensitiveFieldName(parser, registry);
                    if (field != null) {
                        generator.writeString(registry.mask(field, parser.getText()));
                    } else {
                        // The literal text, so the number is not reformatted by a double round trip.
                        generator.writeNumber(parser.getText());
                    }
                    break;
                }
                default:
                    // FIELD_NAME, booleans, null.
                    generator.copyCurrentEvent(parser);
                    break;
            }
        } while (depth > 0 && parser.nextToken() != null);
    }

    /**
     * The nearest enclosing field name that is registered as sensitive, or {@code null} if there is
     * none.
     *
     * <p>Every enclosing field is checked, not just the value's own: an array element has no field
     * name of its own, so {@code kv("token", List.of(a, b))}, serialised as {@code "token":[a,b]},
     * would otherwise be written verbatim, and the same holds for an object nested under a
     * sensitive key.
     */
    private static String sensitiveFieldName(JsonParser parser, MaskingRegistry registry) {
        for (JsonStreamContext context = parser.getParsingContext();
                context != null; context = context.getParent()) {
            if (context.inObject()) {
                String name = context.getCurrentName();
                if (name != null && registry.isSensitiveField(name)) {
                    return name;
                }
            }
        }
        return null;
    }

    // --- Pre-serialisation masking ---

    /**
     * Returns a view of {@code event} whose formatted message and MDC are masked.
     *
     * <p>A dynamic proxy rather than a concrete {@code ILoggingEvent} implementation: this class is
     * compiled against Logback 1.2, and a concrete class would not implement the methods later
     * versions add ({@code getInstant}, {@code getKeyValuePairs}, {@code getMarkerList}, ...) that
     * LogstashEncoder calls on Logback 1.3+. The proxy forwards every method it does not override,
     * whatever the runtime version declares.
     */
    static ILoggingEvent maskedView(ILoggingEvent event, MaskingRegistry registry) {
        return (ILoggingEvent) Proxy.newProxyInstance(
                ILoggingEvent.class.getClassLoader(),
                new Class<?>[]{ILoggingEvent.class},
                new MaskingEventHandler(event, registry));
    }

    private static final class MaskingEventHandler implements InvocationHandler {

        private final ILoggingEvent delegate;
        private final MaskingRegistry registry;
        private String maskedMessage;
        private Map<String, String> maskedMdc;

        MaskingEventHandler(ILoggingEvent delegate, MaskingRegistry registry) {
            this.delegate = delegate;
            this.registry = registry;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getParameterTypes().length == 0) {
                String name = method.getName();
                if ("getFormattedMessage".equals(name)) {
                    return formattedMessage();
                }
                if ("getMDCPropertyMap".equals(name) || "getMdc".equals(name)) {
                    return mdc();
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private String formattedMessage() {
            if (maskedMessage == null) {
                // maskJsonString is a superset of maskMessage: it applies the message patterns, then
                // "field":"value" and field=value pairs, so JSON and form bodies in a message are covered.
                maskedMessage = registry.maskJsonString(delegate.getFormattedMessage());
            }
            return maskedMessage;
        }

        private Map<String, String> mdc() {
            if (maskedMdc == null) {
                Map<String, String> raw = delegate.getMDCPropertyMap();
                if (raw == null || raw.isEmpty()) {
                    maskedMdc = raw == null ? Collections.<String, String>emptyMap() : raw;
                } else {
                    Map<String, String> masked = new LinkedHashMap<String, String>(raw.size() * 2);
                    for (Map.Entry<String, String> entry : raw.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        masked.put(key, registry.isSensitiveField(key)
                                ? registry.mask(key, value)
                                : registry.maskJsonString(value));
                    }
                    maskedMdc = Collections.unmodifiableMap(masked);
                }
            }
            return maskedMdc;
        }
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
