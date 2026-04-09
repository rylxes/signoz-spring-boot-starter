package io.signoz.springboot.detect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether the OpenTelemetry Java Agent ({@code opentelemetry-javaagent.jar})
 * is active in the current JVM.
 *
 * <p>When the agent is present, the starter skips its own OTLP export (traces, logs,
 * metrics) to avoid duplicate data. App-level features (masking, audit, request logging)
 * remain active regardless.
 *
 * <p>Detection uses three strategies (checked in order):
 * <ol>
 *   <li>System property {@code otel.javaagent.version} — set by the agent at startup</li>
 *   <li>Class presence {@code io.opentelemetry.javaagent.OpenTelemetryAgent}</li>
 *   <li>{@code GlobalOpenTelemetry} already initialized (non-noop)</li>
 * </ol>
 *
 * <p>The result is cached for the lifetime of the JVM (agent presence cannot change).
 */
public final class AgentDetector {

    private static final Logger log = LoggerFactory.getLogger(AgentDetector.class);

    private static volatile Boolean cached;

    private AgentDetector() {
        // utility class
    }

    /**
     * Returns {@code true} if the OpenTelemetry Java Agent is active.
     */
    public static boolean isAgentPresent() {
        Boolean result = cached;
        if (result != null) {
            return result;
        }
        synchronized (AgentDetector.class) {
            if (cached != null) {
                return cached;
            }
            cached = detect();
            if (cached) {
                log.info("[SigNoz] OpenTelemetry Java Agent detected — "
                        + "starter will defer OTLP export to the agent");
            }
            return cached;
        }
    }

    private static boolean detect() {
        // Strategy 1: System property set by the agent
        String version = System.getProperty("otel.javaagent.version");
        if (version != null && !version.isEmpty()) {
            log.debug("[SigNoz] Agent detected via system property otel.javaagent.version={}", version);
            return true;
        }

        // Strategy 2: Agent class on classpath
        try {
            Class.forName("io.opentelemetry.javaagent.OpenTelemetryAgent", false,
                    ClassLoader.getSystemClassLoader());
            log.debug("[SigNoz] Agent detected via class presence");
            return true;
        } catch (ClassNotFoundException ignored) {
            // not present
        }

        // Strategy 3: GlobalOpenTelemetry already initialized by the agent
        try {
            Object global = io.opentelemetry.api.GlobalOpenTelemetry.get();
            if (global != null && !global.getClass().getSimpleName().contains("Noop")) {
                log.debug("[SigNoz] Agent detected via GlobalOpenTelemetry (non-noop)");
                return true;
            }
        } catch (Exception ignored) {
            // not initialized or error
        }

        return false;
    }

    /**
     * Clears the cached detection result. For testing only.
     */
    static void resetCache() {
        cached = null;
    }
}
