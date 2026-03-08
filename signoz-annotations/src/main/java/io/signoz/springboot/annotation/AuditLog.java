package io.signoz.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a structured audit-trail entry every time the annotated method is invoked.
 *
 * <p>The entry is built by {@code AuditLogAspect} and published as a Spring
 * {@code AuditEvent}. The default handler ({@code SigNozAuditHandler}) logs it
 * via SLF4J with the {@code AUDIT} marker so it appears in SigNoz logs with
 * full trace correlation.
 *
 * <p>Fields captured automatically:
 * <ul>
 *   <li>{@code traceId} / {@code spanId} — from the active OpenTelemetry span</li>
 *   <li>{@code actor} — from {@code SecurityContextHolder} (if Spring Security present)</li>
 *   <li>{@code timestamp} — UTC instant of the call</li>
 *   <li>{@code outcome} — SUCCESS or FAILURE (on exception)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}AuditLog(action = "USER_LOGIN", resourceType = "User", captureArgs = false)
 *   public AuthResult login(String username, String password) { ... }
 *
 *   {@literal @}AuditLog(action = "TRANSFER_FUNDS", resourceType = "Account", captureArgs = true)
 *   public Transfer transfer(String fromId, String toId, BigDecimal amount) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * The action name recorded in the audit entry (e.g. {@code "USER_LOGIN"},
     * {@code "DELETE_RECORD"}). Should be an uppercase, underscore-separated verb.
     */
    String action();

    /**
     * The type of resource being acted upon (e.g. {@code "User"}, {@code "Order"}).
     */
    String resourceType() default "";

    /**
     * SpEL expression evaluated against the method arguments to extract the resource ID.
     * Example: {@code resourceId = "#order.id"} when the method takes an {@code Order} argument.
     * Leave empty to omit the resource ID.
     */
    String resourceId() default "";

    /**
     * Whether to capture and log method arguments in the audit entry.
     * Disable for methods that accept sensitive parameters (passwords, tokens).
     * Defaults to {@code true}.
     */
    boolean captureArgs() default true;

    /**
     * Whether to capture the return value in the audit entry.
     * Defaults to {@code false} to avoid logging large response objects.
     */
    boolean captureResult() default false;
}
