package io.signoz.springboot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for user context enrichment under {@code signoz.user-context.*}.
 *
 * <p>When enabled, the starter extracts user identity information from Spring Security's
 * {@code SecurityContextHolder} and places it into the SLF4J MDC for every request.
 *
 * <pre>
 * signoz:
 *   user-context:
 *     enabled: true
 *     extract-email: true
 *     extract-roles: true
 *     principal-field: email
 * </pre>
 */
@ConfigurationProperties(prefix = "signoz.user-context")
public class SigNozUserContextProperties {

    /** Whether user context enrichment is enabled. Defaults to {@code true}. */
    private boolean enabled = true;

    /** Whether to extract the user's email from the principal. Defaults to {@code true}. */
    private boolean extractEmail = true;

    /** Whether to extract the user's roles/authorities. Defaults to {@code true}. */
    private boolean extractRoles = true;

    /**
     * The field name to use when extracting the principal identifier.
     * Defaults to {@code "email"}.
     */
    private String principalField = "email";

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isExtractEmail() { return extractEmail; }
    public void setExtractEmail(boolean extractEmail) { this.extractEmail = extractEmail; }

    public boolean isExtractRoles() { return extractRoles; }
    public void setExtractRoles(boolean extractRoles) { this.extractRoles = extractRoles; }

    public String getPrincipalField() { return principalField; }
    public void setPrincipalField(String principalField) { this.principalField = principalField; }
}
