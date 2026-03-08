package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit log configuration under {@code signoz.audit.*}.
 *
 * <pre>
 * signoz:
 *   audit:
 *     enabled: true
 *     capture-args: true
 *     capture-result: false
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.audit")
public class SigNozAuditProperties {

    /** Whether the audit log feature is enabled globally. Defaults to {@code true}. */
    private boolean enabled = true;

    /**
     * Global default for capturing method arguments in audit entries.
     * Individual {@code @AuditLog} annotations can override this per method.
     * Defaults to {@code true}.
     */
    private boolean captureArgs = true;

    /**
     * Global default for capturing method return values in audit entries.
     * Defaults to {@code false} to avoid logging large response objects.
     */
    private boolean captureResult = false;

    /**
     * Whether to include the current thread name in the audit entry.
     * Useful for async processing. Defaults to {@code false}.
     */
    private boolean includeThread = false;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isCaptureArgs() { return captureArgs; }
    public void setCaptureArgs(boolean captureArgs) { this.captureArgs = captureArgs; }

    public boolean isCaptureResult() { return captureResult; }
    public void setCaptureResult(boolean captureResult) { this.captureResult = captureResult; }

    public boolean isIncludeThread() { return includeThread; }
    public void setIncludeThread(boolean includeThread) { this.includeThread = includeThread; }
}
