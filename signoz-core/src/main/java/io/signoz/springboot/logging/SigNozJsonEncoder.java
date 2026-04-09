package io.signoz.springboot.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.signoz.springboot.masking.MaskingRegistry;
import net.logstash.logback.encoder.LogstashEncoder;

import java.io.IOException;

/**
 * Custom Logback encoder that wraps {@link LogstashEncoder} (Logstash-compatible JSON)
 * and applies {@link MaskingRegistry} masking to every log record before serialisation.
 *
 * <p>The masking pipeline:
 * <ol>
 *   <li>The parent {@code LogstashEncoder} serialises the event to a JSON byte array.</li>
 *   <li>The JSON string is passed through {@link MaskingRegistry#maskJsonString(String)}
 *       which replaces sensitive field values in the JSON.</li>
 *   <li>The masked JSON bytes are returned to the appender.</li>
 * </ol>
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
        byte[] originalBytes = super.encode(event);

        if (maskingRegistry == null || originalBytes == null) {
            return originalBytes;
        }

        try {
            String json = new String(originalBytes, "UTF-8");
            String maskedJson = maskingRegistry.maskJsonString(json);
            return maskedJson.getBytes("UTF-8");
        } catch (Exception e) {
            // On any error, return original unmasked bytes rather than dropping the log
            return originalBytes;
        }
    }

    @Override
    public void encode(ILoggingEvent event, java.io.OutputStream outputStream) throws IOException {
        byte[] encoded = encode(event);
        if (encoded != null) {
            outputStream.write(encoded);
        }
    }
}
