package io.signoz.springboot.usercontext;

import io.signoz.springboot.properties.SigNozUserContextProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Extracts user identity information from Spring Security's {@code SecurityContextHolder}
 * and places it into the SLF4J MDC.
 *
 * <p>Uses reflection to access Spring Security classes, avoiding a hard compile-time
 * dependency. If Spring Security is not on the classpath, enrichment is silently skipped.
 *
 * <p>MDC keys populated:
 * <ul>
 *   <li>{@code userId} — the principal name or configured field value</li>
 *   <li>{@code userEmail} — the user's email (if {@code extractEmail} is enabled)</li>
 *   <li>{@code userRoles} — comma-separated list of granted authorities</li>
 * </ul>
 */
public class UserContextEnricher {

    private static final Logger log = LoggerFactory.getLogger(UserContextEnricher.class);

    private static final String MDC_USER_ID = "userId";
    private static final String MDC_USER_EMAIL = "userEmail";
    private static final String MDC_USER_ROLES = "userRoles";

    private final SigNozUserContextProperties properties;

    /**
     * Creates a new enricher.
     *
     * @param properties the user context configuration properties
     */
    public UserContextEnricher(SigNozUserContextProperties properties) {
        this.properties = properties;
    }

    /**
     * Extracts user information from the current Spring Security context and
     * populates the SLF4J MDC. Safe to call even when Spring Security is not
     * on the classpath.
     */
    public void enrichMdc() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Class<?> holderClass = Class.forName(
                    "org.springframework.security.core.context.SecurityContextHolder");
            Object context = holderClass.getMethod("getContext").invoke(null);
            if (context == null) {
                return;
            }

            Object authentication = context.getClass()
                    .getMethod("getAuthentication").invoke(context);
            if (authentication == null) {
                return;
            }

            // Check if authenticated (not anonymous)
            Object isAuthenticated = authentication.getClass()
                    .getMethod("isAuthenticated").invoke(authentication);
            if (!Boolean.TRUE.equals(isAuthenticated)) {
                return;
            }

            // Extract userId (principal name)
            Object principal = authentication.getClass()
                    .getMethod("getPrincipal").invoke(authentication);
            if (principal != null) {
                String userId = extractPrincipalField(principal);
                if (userId != null) {
                    MDC.put(MDC_USER_ID, userId);
                }

                // Extract email if enabled
                if (properties.isExtractEmail()) {
                    String email = extractEmail(principal);
                    if (email != null) {
                        MDC.put(MDC_USER_EMAIL, email);
                    }
                }
            }

            // Extract roles if enabled
            if (properties.isExtractRoles()) {
                String roles = extractRoles(authentication);
                if (roles != null && !roles.isEmpty()) {
                    MDC.put(MDC_USER_ROLES, roles);
                }
            }

        } catch (ClassNotFoundException e) {
            log.debug("[SigNoz] Spring Security not on classpath, skipping user context enrichment");
        } catch (Exception e) {
            log.debug("[SigNoz] Could not extract user context: {}", e.getMessage());
        }
    }

    /**
     * Removes all user context keys from the SLF4J MDC.
     */
    public void clearMdc() {
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_USER_EMAIL);
        MDC.remove(MDC_USER_ROLES);
    }

    /**
     * Extracts the configured principal field from the principal object.
     * Falls back to {@code toString()} if the field is not accessible.
     */
    private String extractPrincipalField(Object principal) {
        if (principal instanceof String) {
            return (String) principal;
        }
        String field = properties.getPrincipalField();
        // Try getter method: e.g. "email" -> "getEmail"
        try {
            String getterName = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Method getter = principal.getClass().getMethod(getterName);
            Object value = getter.invoke(principal);
            if (value != null) {
                return value.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        // Try getName() as fallback
        try {
            Method getName = principal.getClass().getMethod("getName");
            Object value = getName.invoke(principal);
            if (value != null) {
                return value.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return principal.toString();
    }

    /**
     * Extracts the email from the principal. Tries {@code getEmail()} method via reflection.
     */
    private String extractEmail(Object principal) {
        if (principal instanceof String) {
            // If principal is a plain string, it may already be an email
            String str = (String) principal;
            if (str.contains("@")) {
                return str;
            }
            return null;
        }
        try {
            Method getEmail = principal.getClass().getMethod("getEmail");
            Object email = getEmail.invoke(principal);
            if (email != null) {
                return email.toString();
            }
        } catch (Exception ignored) {
            // email method not available
        }
        return null;
    }

    /**
     * Extracts granted authorities from the authentication object and returns
     * them as a comma-separated string.
     */
    @SuppressWarnings("unchecked")
    private String extractRoles(Object authentication) {
        try {
            Object authorities = authentication.getClass()
                    .getMethod("getAuthorities").invoke(authentication);
            if (authorities instanceof Collection) {
                Collection<?> authCollection = (Collection<?>) authorities;
                if (authCollection.isEmpty()) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object auth : authCollection) {
                    Object authority = auth.getClass()
                            .getMethod("getAuthority").invoke(auth);
                    if (authority != null) {
                        if (!first) {
                            sb.append(",");
                        }
                        sb.append(authority.toString());
                        first = false;
                    }
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // could not extract roles
        }
        return null;
    }
}
