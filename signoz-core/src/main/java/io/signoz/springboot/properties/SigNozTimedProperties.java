package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the {@code @Timed} annotation feature
 * under {@code signoz.timed.*}.
 *
 * <pre>
 * signoz:
 *   timed:
 *     enabled: true
 *     slow-threshold-ms: 1000
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.timed")
public class SigNozTimedProperties {

    /** Whether the timed aspect is enabled globally. Defaults to {@code true}. */
    private boolean enabled = true;

    /**
     * Duration threshold in milliseconds. Method executions that exceed this
     * value are logged at WARN level as "slow". Defaults to {@code 1000} (1 second).
     */
    private long slowThresholdMs = 1000;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getSlowThresholdMs() { return slowThresholdMs; }
    public void setSlowThresholdMs(long slowThresholdMs) { this.slowThresholdMs = slowThresholdMs; }
}
