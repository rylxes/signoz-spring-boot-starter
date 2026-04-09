package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Error-tracking configuration nested under {@code signoz.errors.*}.
 *
 * <pre>
 * signoz:
 *   errors:
 *     enabled: true
 *     fingerprint-depth: 3
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.errors")
public class SigNozErrorProperties {

    /** Whether error fingerprinting is enabled. Defaults to {@code true}. */
    private boolean enabled = true;

    /**
     * Number of top stack-trace frames used when computing an error fingerprint.
     * Defaults to {@code 3}.
     */
    private int fingerprintDepth = 3;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getFingerprintDepth() { return fingerprintDepth; }
    public void setFingerprintDepth(int fingerprintDepth) { this.fingerprintDepth = fingerprintDepth; }
}
