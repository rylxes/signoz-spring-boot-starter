package io.signoz.springboot.detect;

import java.lang.reflect.Field;

import io.opentelemetry.api.OpenTelemetry;
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
 *   <li>{@code GlobalOpenTelemetry} already holds a non-noop instance, read via
 *       reflection on its private static field. {@code GlobalOpenTelemetry.get()} must
 *       not be called here: when nothing has been registered yet, {@code get()}
 *       auto-installs a noop and locks out any future {@code set()} (or
 *       {@code OpenTelemetrySdkBuilder.buildAndRegisterGlobal()}) call, which would
 *       break the starter's own SDK setup when no agent is present.</li>
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

        // Strategy 3: GlobalOpenTelemetry already holds a non-noop instance.
        //
        // Read the private static field directly via reflection — calling
        // GlobalOpenTelemetry.get() would trigger maybeAutoConfigureAndSetGlobal(),
        // which installs a noop and prevents the starter's own SDK config from
        // calling buildAndRegisterGlobal() later (IllegalStateException at startup).
        //
        // Field layout (opentelemetry-api 1.x):
        //   GlobalOpenTelemetry.globalOpenTelemetry : ObfuscatedOpenTelemetry  (nullable)
        //   ObfuscatedOpenTelemetry.delegate        : OpenTelemetry
        //
        // Null field => nothing registered yet => no agent (this is the path the
        // starter takes when running standalone, and we must not perturb it).
        OpenTelemetry registered = readRegisteredGlobal();
        if (registered != null && registered != OpenTelemetry.noop()) {
            log.debug("[SigNoz] Agent detected via GlobalOpenTelemetry (non-noop): {}",
                    registered.getClass().getName());
            return true;
        }

        return false;
    }

    private static io.opentelemetry.api.OpenTelemetry readRegisteredGlobal() {
        try {
            Field globalField = io.opentelemetry.api.GlobalOpenTelemetry.class
                    .getDeclaredField("globalOpenTelemetry");
            globalField.setAccessible(true);
            Object obfuscated = globalField.get(null);
            if (obfuscated == null) {
                return null;
            }
            Field delegateField = obfuscated.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(obfuscated);
            return (delegate instanceof io.opentelemetry.api.OpenTelemetry)
                    ? (io.opentelemetry.api.OpenTelemetry) delegate
                    : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Field layout changed (newer OTel API), or reflection blocked by the
            // module system. Fall back to "not detected" rather than calling .get(),
            // which would corrupt the global. The starter still works without
            // strategy 3 — strategies 1 and 2 cover the agent case.
            log.debug("[SigNoz] Could not read GlobalOpenTelemetry via reflection: {}",
                    e.toString());
            return null;
        }
    }

    /**
     * Clears the cached detection result. <b>For testing only</b> — production
     * code must not call this; agent presence cannot change at runtime.
     */
    public static void resetCache() {
        cached = null;
    }
}
