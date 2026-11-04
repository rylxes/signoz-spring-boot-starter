package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import net.logstash.logback.argument.StructuredArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The encoder's second masking pass runs over the line LogstashEncoder has already serialised.
 * That line has no whitespace between fields, so a free-text pattern such as
 * {@code password=\S+} at the end of a string value used to run through the closing quote and
 * swallow the rest of the line: the output was no longer JSON and SigNoz lost level, logger and MDC.
 * These tests pin the line's structure as well as the masking.
 */
class SigNozJsonEncoderLineIntegrityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoggerContext loggerContext;
    private SigNozJsonEncoder encoder;

    @BeforeEach
    void setUp() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        encoder = new SigNozJsonEncoder();
        encoder.setContext(loggerContext);
        encoder.setMaskingRegistry(new MaskingRegistry(new SigNozLoggingProperties()));
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        encoder.stop();
    }

    @Test
    void passwordAsLastTokenOfMessageKeepsLineValidJson() throws Exception {
        String line = encode(event("login password=s3cret", null, null, null));

        assertThat(line).doesNotContain("s3cret");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo("login password=***");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger_name").asText()).isEqualTo("probe");
        assertThat(line).endsWith(System.lineSeparator());
    }

    @Test
    void passwordAsLastTokenOfMdcValueKeepsFollowingFields() throws Exception {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("note", "user password=s3cret");
        mdc.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

        String line = encode(event("request received", null, mdc, null));

        assertThat(line).doesNotContain("s3cret");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("note").asText()).isEqualTo("user password=***");
        assertThat(node.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
    }

    @Test
    void escapesInStackTraceAreNotConsumedByMessagePatterns() throws Exception {
        String line = encode(event("failed", null, null, new IllegalStateException("bad password=s3cret")));

        assertThat(line).doesNotContain("s3cret");
        JsonNode node = JSON.readTree(line);
        String stackTrace = node.get("stack_trace").asText();
        assertThat(stackTrace).startsWith("java.lang.IllegalStateException: bad password=***\n\tat ");
        assertThat(node.get("logger_name").asText()).isEqualTo("probe");
    }

    @Test
    void sensitiveStructuredArgumentIsMaskedByFieldName() throws Exception {
        String line = encode(event("login {}",
                new Object[]{StructuredArguments.keyValue("token", "tok-abcdef")}, null, null));

        assertThat(line).doesNotContain("tok-abcdef");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("token").asText()).isEqualTo("***");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
    }

    /**
     * An array element has no field name of its own, so a list under a sensitive key used to be
     * written verbatim, and its rendering in the message ({@code token=[a, b]}) was masked only up
     * to the first comma.
     */
    @Test
    void listUnderSensitiveKeyIsMaskedInFieldAndMessage() throws Exception {
        String line = encode(event("rotated {}", new Object[]{StructuredArguments.keyValue(
                "token", Arrays.asList("tok-aaaa1111", "tok-bbbb2222"))}, null, null));

        assertThat(line).doesNotContain("tok-aaaa1111").doesNotContain("tok-bbbb2222");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("token").isArray()).isTrue();
        assertThat(node.get("token").get(0).asText()).isEqualTo("***");
        assertThat(node.get("token").get(1).asText()).isEqualTo("***");
        assertThat(node.get("message").asText()).isEqualTo("rotated token=***");
    }

    @Test
    void objectNestedUnderSensitiveKeyIsMasked() throws Exception {
        String line = encode(event("issued {}", new Object[]{StructuredArguments.keyValue(
                "token", Collections.singletonMap("value", "tok-cccc3333"))}, null, null));

        assertThat(line).doesNotContain("tok-cccc3333");
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("token").get("value").asText()).isEqualTo("***");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
    }

    /**
     * With a prefix the line is not plain JSON. The object after the prefix must still get the
     * per-value pass: regexes over the raw line miss JSON escaped inside the message and cannot see
     * a list under a sensitive key at all.
     */
    @Test
    void prefixedLineIsStillMaskedValueByValue() throws Exception {
        SigNozJsonEncoder prefixed = new SigNozJsonEncoder();
        prefixed.setContext(loggerContext);
        prefixed.setMaskingRegistry(new MaskingRegistry(new SigNozLoggingProperties()));
        PatternLayoutEncoder prefix = new PatternLayoutEncoder();
        prefix.setContext(loggerContext);
        prefix.setPattern("APP ");
        prefix.start();
        prefixed.setPrefix(prefix);
        prefixed.start();
        try {
            String line = new String(prefixed.encode(event("login {} {}", new Object[]{
                    "{\"user\":\"alice\",\"token\":\"tok-dddd4444\"}",
                    StructuredArguments.keyValue("token", Arrays.asList("tok-eeee5555", "tok-ffff6666"))},
                    null, null)), StandardCharsets.UTF_8);

            assertThat(line).startsWith("APP {").contains("alice")
                    .doesNotContain("tok-dddd4444").doesNotContain("tok-eeee5555").doesNotContain("tok-ffff6666");
            JsonNode node = JSON.readTree(line.substring("APP ".length()));
            assertThat(node.get("message").asText())
                    .isEqualTo("login {\"user\":\"alice\",\"token\":\"***\"} token=***");
            assertThat(node.get("token").get(0).asText()).isEqualTo("***");
            assertThat(node.get("level").asText()).isEqualTo("INFO");
        } finally {
            prefixed.stop();
            prefix.stop();
        }
    }

    private String encode(LoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private LoggingEvent event(String message, Object[] args, Map<String, String> mdc, Throwable error) {
        LoggingEvent event = new LoggingEvent(
                "x", loggerContext.getLogger("probe"), Level.INFO, message, error, args);
        if (mdc != null) {
            event.setMDCPropertyMap(mdc);
        }
        return event;
    }
}
