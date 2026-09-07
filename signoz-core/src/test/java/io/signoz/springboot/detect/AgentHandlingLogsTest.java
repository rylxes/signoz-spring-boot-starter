package io.signoz.springboot.detect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentDetector#isAgentHandlingLogs()} decides whether this starter attaches its own
 * (masking) OTLP log appender.
 *
 * <p>The distinction matters because agent presence and agent <em>log export</em> are separate
 * things. A service that wants agent traces but masked logs disables only the agent's logback
 * appender; if the starter still stood down, that service would silently lose OTLP logs entirely.
 */
class AgentHandlingLogsTest {

    private static final String AGENT_VERSION = "otel.javaagent.version";
    private static final String APPENDER_ENABLED = "otel.instrumentation.logback-appender.enabled";
    private static final String LOGS_EXPORTER = "otel.logs.exporter";

    private void pretendAgentPresent(boolean present) throws Exception {
        if (present) {
            System.setProperty(AGENT_VERSION, "2.10.0");
        } else {
            System.clearProperty(AGENT_VERSION);
        }
        // AgentDetector caches its answer for the JVM lifetime; reset it between cases.
        Field cached = AgentDetector.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, null);
    }

    @AfterEach
    void clearProps() throws Exception {
        System.clearProperty(AGENT_VERSION);
        System.clearProperty(APPENDER_ENABLED);
        System.clearProperty(LOGS_EXPORTER);
        Field cached = AgentDetector.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, null);
    }

    @Test
    @DisplayName("no agent -> starter owns log export")
    void noAgent() throws Exception {
        pretendAgentPresent(false);
        assertThat(AgentDetector.isAgentHandlingLogs()).isFalse();
    }

    @Test
    @DisplayName("agent present with defaults -> agent owns log export")
    void agentWithDefaults() throws Exception {
        pretendAgentPresent(true);
        assertThat(AgentDetector.isAgentHandlingLogs()).isTrue();
    }

    @Test
    @DisplayName("agent present but its logback appender disabled -> starter owns log export")
    void agentWithLogbackAppenderDisabled() throws Exception {
        pretendAgentPresent(true);
        System.setProperty(APPENDER_ENABLED, "false");
        // This is the combination a service uses to keep agent traces while getting masked logs.
        assertThat(AgentDetector.isAgentHandlingLogs()).isFalse();
    }

    @Test
    @DisplayName("agent present but logs exporter is none -> starter owns log export")
    void agentWithLogsExporterNone() throws Exception {
        pretendAgentPresent(true);
        System.setProperty(LOGS_EXPORTER, "none");
        assertThat(AgentDetector.isAgentHandlingLogs()).isFalse();
    }

    @Test
    @DisplayName("an explicit true is respected, and unrelated values do not disable")
    void explicitEnableAndUnrelatedValues() throws Exception {
        pretendAgentPresent(true);
        System.setProperty(APPENDER_ENABLED, "true");
        assertThat(AgentDetector.isAgentHandlingLogs()).isTrue();

        System.clearProperty(APPENDER_ENABLED);
        System.setProperty(LOGS_EXPORTER, "otlp");
        assertThat(AgentDetector.isAgentHandlingLogs()).isTrue();
    }
}
