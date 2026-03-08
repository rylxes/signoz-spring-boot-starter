package io.signoz.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wraps the annotated method (or all methods of the annotated type) in an
 * OpenTelemetry span, enabling distributed tracing visible in SigNoz.
 *
 * <p>The span lifecycle is managed by {@code TracedAspect}:
 * <ul>
 *   <li>Span is started before the method executes.</li>
 *   <li>If the method throws, the exception is recorded on the span and re-thrown.</li>
 *   <li>The span is ended in a {@code finally} block regardless of outcome.</li>
 * </ul>
 *
 * <p>Usage on a method:
 * <pre>
 *   {@literal @}Traced(operationName = "payment.checkout", tags = {"domain=payment", "critical=true"})
 *   public Order checkout(Cart cart) {
 *       // entire method body is within a named span
 *   }
 * </pre>
 *
 * <p>Usage on a class (traces all public methods):
 * <pre>
 *   {@literal @}Traced
 *   {@literal @}Service
 *   public class InventoryService { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Traced {

    /**
     * Name of the OpenTelemetry span. Defaults to {@code ClassName.methodName}
     * when empty.
     */
    String operationName() default "";

    /**
     * Key=value pairs attached as span attributes, e.g.
     * {@code tags = {"domain=payment", "version=2"}}.
     */
    String[] tags() default {};

    /**
     * Whether to record exceptions thrown by the method on the span.
     * Defaults to {@code true}.
     */
    boolean recordException() default true;
}
