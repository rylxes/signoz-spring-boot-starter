package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Log-sampling configuration nested under {@code signoz.logging.sampling.*}.
 *
 * <p>Sampling is <strong>opt-in</strong>: it is disabled by default. When enabled,
 * log events whose level is not in {@link #alwaysLogLevels} are accepted with
 * probability {@link #rate}.</p>
 *
 * <pre>
 * signoz:
 *   logging:
 *     sampling:
 *       enabled: false
 *       rate: 1.0
 *       always-log-levels:
 *         - ERROR
 *         - WARN
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.logging.sampling")
public class SigNozSamplingProperties {

    /** Whether log sampling is enabled. Defaults to {@code false} (opt-in). */
    private boolean enabled = false;

    /**
     * Probability (0.0 to 1.0) that a non-exempt log event is accepted.
     * Defaults to {@code 1.0} (accept all).
     */
    private double rate = 1.0;

    /**
     * Log levels that are always accepted regardless of the sampling rate.
     * Defaults to {@code ERROR} and {@code WARN}.
     */
    private List<String> alwaysLogLevels = new ArrayList<>(Arrays.asList("ERROR", "WARN"));

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getRate() { return rate; }
    public void setRate(double rate) { this.rate = rate; }

    public List<String> getAlwaysLogLevels() { return alwaysLogLevels; }
    public void setAlwaysLogLevels(List<String> alwaysLogLevels) { this.alwaysLogLevels = alwaysLogLevels; }
}
