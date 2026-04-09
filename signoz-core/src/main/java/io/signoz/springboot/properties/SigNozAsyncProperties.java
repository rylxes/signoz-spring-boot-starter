package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for async context propagation under {@code signoz.async.*}.
 *
 * <p>When enabled, the starter decorates task executors so that MDC context
 * (trace IDs, user info, etc.) is automatically propagated to async threads.
 *
 * <pre>
 * signoz:
 *   async:
 *     enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.async")
public class SigNozAsyncProperties {

    /** Whether async MDC context propagation is enabled. Defaults to {@code true}. */
    private boolean enabled = true;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
