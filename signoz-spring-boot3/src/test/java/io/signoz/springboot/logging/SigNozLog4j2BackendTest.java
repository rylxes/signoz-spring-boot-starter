package io.signoz.springboot.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signoz.springboot.TestSigNozApplication;
import io.signoz.springboot.logging.log4j2.SigNozLog4j2JsonLayout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs only in the {@code log4j2-backend} surefire execution, where Logback and
 * {@code log4j-to-slf4j} are removed from the classpath and {@code spring-boot-starter-log4j2}
 * makes Log4j2 the real logging backend, exactly as in an application that switched to it.
 */
@SpringBootTest(classes = TestSigNozApplication.class,
        properties = {"spring.main.web-application-type=none", "signoz.logging.mode=JSON"})
@ActiveProfiles("test")
class SigNozLog4j2BackendTest {

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String SENSITIVE = "sensitive-" + "payload-value";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapturingAppender capture;

    @AfterEach
    void detach() {
        MDC.clear();
        if (capture != null) {
            root().removeAppender(capture.getName());
            context().updateLoggers();
        }
    }

    @Test
    void log4j2IsTheBackendAndTheStarterReplacedTheDefaultConsole() {
        assertThat(LoggerFactory.getILoggerFactory().getClass().getName()).startsWith("org.apache.logging.slf4j");
        assertThat(root().getAppenders()).containsKey("SIGNOZ_JSON").doesNotContainKey("Console");
        assertThat(root().getAppenders().get("SIGNOZ_JSON").getLayout()).isInstanceOf(SigNozLog4j2JsonLayout.class);
    }

    @Test
    void slf4jLogsBecomeMaskedJsonCarryingTheMdc() throws Exception {
        Layout<? extends Serializable> layout = root().getAppenders().get("SIGNOZ_JSON").getLayout();
        capture = new CapturingAppender(layout);
        capture.start();
        root().addAppender(capture, null, null);
        context().updateLoggers();

        MDC.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("requestId", "req-l4j");
        LoggerFactory.getLogger("com.example.Login").info("login {}", "{\"user\":\"alice\",\"password\":\"" + SENSITIVE + "\"}");
        LoggerFactory.getLogger("com.example.Login").error("failed", new IllegalStateException("boom"));

        assertThat(capture.lines).hasSize(2);
        JsonNode info = JSON.readTree(capture.lines.get(0));
        assertThat(info.get("message").asText()).contains("alice").doesNotContain(SENSITIVE);
        assertThat(info.get("trace_id").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(info.get("requestId").asText()).isEqualTo("req-l4j");
        assertThat(info.get("logger_name").asText()).isEqualTo("com.example.Login");

        JsonNode error = JSON.readTree(capture.lines.get(1));
        assertThat(error.get("errorId").asText()).matches("[0-9a-f]{12}");
        assertThat(error.get("stack_trace").asText()).contains("IllegalStateException: boom");
    }

    private static LoggerContext context() {
        return (LoggerContext) LogManager.getContext(false);
    }

    private static LoggerConfig root() {
        return context().getConfiguration().getRootLogger();
    }

    /** Renders each event with the given layout, i.e. exactly what SIGNOZ_JSON writes. */
    static final class CapturingAppender extends AbstractAppender {
        final List<String> lines = new ArrayList<>();

        CapturingAppender(Layout<? extends Serializable> layout) {
            super("CAPTURE", null, layout, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(String.valueOf(getLayout().toSerializable(event)));
        }
    }
}
