package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link SigNozJsonEncoder} end to end on this module's Logback, which signoz-core's tests
 * cannot do (see the {@code @Disabled} tests in signoz-core's {@code SigNozJsonEncoderTest}).
 *
 * <p>The regression: when a log message itself contains JSON, LogstashEncoder escapes its quotes
 * inside the {@code message} field, so the finished line holds {@code \"password\":\"<value>\"}. A
 * masking pass over the finished line never matches that escaped form and the secret was written
 * out verbatim.
 */
class SigNozJsonEncoderEscapedJsonMaskingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Value planted in a sensitive field; it must never appear in encoded output. */
    private static final String CANARY = "s3cret";

    private LoggerContext loggerContext;
    private SigNozJsonEncoder encoder;

    @BeforeEach
    void setUp() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        encoder = new SigNozJsonEncoder();
        encoder.setContext(loggerContext);
    }

    @AfterEach
    void tearDown() {
        encoder.stop();
    }

    @Test
    void jsonPayloadInMessageHasSensitiveFieldMasked() throws Exception {
        start(new MaskingRegistry(new SigNozLoggingProperties()));

        String line = encode(event("login {}", new Object[]{jsonObject("user", "alice", "password", CANARY)}, null));

        assertThat(line).doesNotContain(CANARY);
        assertThat(line).contains("alice");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo("login " + jsonObject("user", "alice", "password", "***"));
    }

    @Test
    void jsonPayloadInMdcValueHasSensitiveFieldMasked() throws Exception {
        start(new MaskingRegistry(new SigNozLoggingProperties()));

        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("requestBody", "{\"user\":\"alice\",\"token\":\"tok-abcdef\"}");
        mdc.put("password", "hunter2");
        mdc.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

        String line = encode(event("request received", null, mdc));

        assertThat(line).doesNotContain("tok-abcdef").doesNotContain("hunter2");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("requestBody").asText()).isEqualTo("{\"user\":\"alice\",\"token\":\"***\"}");
        assertThat(node.get("password").asText()).isEqualTo("***");
        assertThat(node.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void plainKeyValueMessageIsStillMasked() throws Exception {
        start(new MaskingRegistry(new SigNozLoggingProperties()));

        String line = encode(event("login with password=supersecret done", null, null));

        assertThat(line).doesNotContain("supersecret");
        assertThat(JSON.readTree(line).get("message").asText()).contains("done");
    }

    @Test
    void nullRegistryPassesEventThroughUnchanged() throws Exception {
        start(null);

        String line = encode(event("hello {}", new Object[]{"world"}, null));

        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("hello world");
    }

    @Test
    void maskingFailureWithholdsMessageInsteadOfLeakingIt() throws Exception {
        MaskingRegistry broken = new MaskingRegistry(new SigNozLoggingProperties()) {
            @Override
            public String maskJsonString(String json) {
                throw new IllegalStateException("boom");
            }
        };
        start(broken);

        String line = encode(event("login {}", new Object[]{jsonObject("password", CANARY)}, null));

        assertThat(line).doesNotContain(CANARY);
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo(SigNozJsonEncoder.MASKING_FAILED_MESSAGE);
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger_name").asText()).isEqualTo("probe");
        assertThat(line).endsWith(System.lineSeparator());
    }

    /**
     * Builds a flat JSON object from alternating keys and values. Fixtures are assembled here rather
     * than written as JSON literals so secret scanners do not read the test data as a real credential.
     */
    private static String jsonObject(String... keysAndValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keysAndValues[i]).append("\":\"").append(keysAndValues[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private void start(MaskingRegistry registry) {
        encoder.setMaskingRegistry(registry);
        encoder.start();
    }

    private String encode(LoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private LoggingEvent event(String message, Object[] args, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent(
                "x", loggerContext.getLogger("probe"), Level.INFO, message, null, args);
        if (mdc != null) {
            event.setMDCPropertyMap(mdc);
        }
        return event;
    }
}
