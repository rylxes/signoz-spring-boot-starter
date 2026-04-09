package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the {@code @AlertOnFailure} annotation feature
 * under {@code signoz.alerts.*}.
 *
 * <pre>
 * signoz:
 *   alerts:
 *     enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.alerts")
public class SigNozAlertProperties {

    /** Whether the alert-on-failure aspect is enabled globally. Defaults to {@code true}. */
    private boolean enabled = true;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
