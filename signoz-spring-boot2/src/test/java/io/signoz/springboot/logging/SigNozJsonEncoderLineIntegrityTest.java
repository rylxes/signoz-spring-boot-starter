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

import java.nio.charset.StandardCharsets;
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

    /** Value planted in a sensitive position; it must never appear in encoded output. */
    private static final String CANARY = "s3cret";

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
        String line = encode(event("login password=" + CANARY, null, null, null));

        assertThat(line).doesNotContain(CANARY);
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("message").asText()).isEqualTo("login password=***");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger_name").asText()).isEqualTo("probe");
        assertThat(line).endsWith(System.lineSeparator());
    }

    @Test
    void passwordAsLastTokenOfMdcValueKeepsFollowingFields() throws Exception {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("note", "user password=" + CANARY);
        mdc.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

        String line = encode(event("request received", null, mdc, null));

        assertThat(line).doesNotContain(CANARY);
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("note").asText()).isEqualTo("user password=***");
        assertThat(node.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
    }

    @Test
    void escapesInStackTraceAreNotConsumedByMessagePatterns() throws Exception {
        String line = encode(event("failed", null, null, new IllegalStateException("bad password=" + CANARY)));

        assertThat(line).doesNotContain(CANARY);
        JsonNode node = JSON.readTree(line);
        String stackTrace = node.get("stack_trace").asText();
        assertThat(stackTrace).startsWith("java.lang.IllegalStateException: bad password=***\n\tat ");
        assertThat(node.get("logger_name").asText()).isEqualTo("probe");
    }

    @Test
    void sensitiveStructuredArgumentIsMaskedByFieldName() throws Exception {
        String line = encode(event("login {}",
                new Object[]{StructuredArguments.keyValue("token", CANARY)}, null, null));

        assertThat(line).doesNotContain(CANARY);
        JsonNode node = JSON.readTree(line);
        assertThat(node.get("token").asText()).isEqualTo("***");
        assertThat(node.get("level").asText()).isEqualTo("INFO");
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
