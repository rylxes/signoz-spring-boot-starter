package io.signoz.springboot.diagnostics;

import io.signoz.springboot.properties.SigNozProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SigNozStartupDiagnostics}.
 */
class SigNozStartupDiagnosticsTest {

    @Test
    void logContainsEndpointAndServiceName() {
        SigNozProperties props = new SigNozProperties();
        props.setEndpoint("http://signoz:4317");
        props.setServiceName("test-service");
        props.setEnvironment("staging");

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("[SigNoz] Config Summary:");
        assertThat(logOutput).contains("endpoint=http://signoz:4317");
        assertThat(logOutput).contains("service=test-service");
        assertThat(logOutput).contains("env=staging");
    }

    @Test
    void logContainsAgentStatus() {
        SigNozProperties props = new SigNozProperties();

        String logOutput = captureLogOutput(props);

        // Agent is not present in test environment
        assertThat(logOutput).containsPattern("agent=(DETECTED|NOT_DETECTED)");
    }

    @Test
    void logContainsMaskingInfo() {
        SigNozProperties props = new SigNozProperties();

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("masking=ON");
        assertThat(logOutput).containsPattern("\\(\\d+ fields\\)");
    }

    @Test
    void logContainsTracingInfo() {
        SigNozProperties props = new SigNozProperties();
        props.getTracing().setEnabled(true);
        props.getTracing().setSampleRate(0.5);

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("tracing=ON");
        assertThat(logOutput).contains("sample=0.5");
    }

    @Test
    void logContainsWebAndAuditStatus() {
        SigNozProperties props = new SigNozProperties();

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("web=ON");
        assertThat(logOutput).contains("audit=ON");
    }

    @Test
    void logContainsAllFeatureLines() {
        SigNozProperties props = new SigNozProperties();

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("timed=");
        assertThat(logOutput).contains("outbound=");
        assertThat(logOutput).contains("messaging=");
        assertThat(logOutput).contains("database=");
        assertThat(logOutput).contains("errors=");
        assertThat(logOutput).contains("sampling=");
        assertThat(logOutput).contains("userContext=");
        assertThat(logOutput).contains("async=");
        assertThat(logOutput).contains("alerts=");
    }

    @Test
    void logContainsDatabaseSlowThreshold() {
        SigNozProperties props = new SigNozProperties();

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).containsPattern("database=\\w+ \\(slow>\\d+ms\\)");
    }

    @Test
    void disabledTracingShowsOff() {
        SigNozProperties props = new SigNozProperties();
        props.getTracing().setEnabled(false);

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("tracing=OFF");
    }

    @Test
    void disabledMaskingShowsOff() {
        SigNozProperties props = new SigNozProperties();
        props.getLogging().setMaskEnabled(false);

        String logOutput = captureLogOutput(props);

        assertThat(logOutput).contains("masking=OFF");
    }

    /**
     * Captures the INFO log output from a fresh diagnostics run.
     */
    private String captureLogOutput(SigNozProperties props) {
        Logger logger = (Logger) LoggerFactory.getLogger(SigNozStartupDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            SigNozStartupDiagnostics diagnostics = new SigNozStartupDiagnostics(props);
            diagnostics.afterSingletonsInstantiated();

            assertThat(appender.list).isNotEmpty();
            return appender.list.get(0).getFormattedMessage();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
