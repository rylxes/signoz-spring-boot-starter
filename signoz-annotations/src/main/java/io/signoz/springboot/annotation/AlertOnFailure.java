package io.signoz.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Increments a failure counter metric every time the annotated method throws
 * an exception, enabling alert rules in SigNoz.
 *
 * <p>The counter lifecycle is managed by {@code AlertOnFailureAspect}:
 * <ul>
 *   <li>If the method completes normally, no counter is touched.</li>
 *   <li>On any {@code Throwable}, the counter identified by {@link #metric()}
 *       is incremented with tags for class name, method name, exception type,
 *       and any additional tags from {@link #tags()}.</li>
 *   <li>The exception is always re-thrown after recording.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}AlertOnFailure(metric = "payment.failures", tags = {"severity=critical"})
 *   public void processPayment(Order order) {
 *       // if this throws, the counter increments
 *   }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AlertOnFailure {

    /**
     * Name of the Micrometer {@code Counter} metric that is incremented on
     * failure. Defaults to {@code "signoz.alerts.failure"}.
     */
    String metric() default "signoz.alerts.failure";

    /**
     * Additional key=value tags applied to the counter, e.g.
     * {@code tags = {"severity=critical", "domain=payment"}}.
     * Tags must be in {@code "key=value"} format; malformed entries are
     * silently skipped.
     */
    String[] tags() default {};
}
