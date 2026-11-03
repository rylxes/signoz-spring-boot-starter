package io.signoz.springboot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.signoz.springboot.masking.MaskingRegistry;
import net.logstash.logback.encoder.LogstashEncoder;

import java.io.IOException;
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
 *   <li>The formatted message and every MDC value are masked <em>before</em> serialisation, while
 *       they are still raw text. This step is what catches JSON embedded in a message: once
 *       {@code LogstashEncoder} has serialised it, its quotes are escaped ({@code \"password\":\"x\"})
 *       and no {@code "field":"value"} pattern matches it any more.</li>
 *   <li>The parent {@code LogstashEncoder} serialises the masked event to a JSON byte array.</li>
 *   <li>The finished line is passed through {@link MaskingRegistry#maskJsonString(String)} as a
 *       second pass over everything else (structured arguments, markers, stack traces).</li>
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
            byte[] bytes = super.encode(maskedView(event, registry));
            if (bytes == null) {
                return null;
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            return registry.maskJsonString(json).getBytes(StandardCharsets.UTF_8);
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
