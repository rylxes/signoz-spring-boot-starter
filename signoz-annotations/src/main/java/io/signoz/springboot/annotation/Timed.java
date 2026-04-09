package io.signoz.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records the execution time of the annotated method (or all methods of the
 * annotated type) as a Micrometer {@code Timer} metric, enabling latency
 * dashboards in SigNoz.
 *
 * <p>The timer lifecycle is managed by {@code TimedAspect}:
 * <ul>
 *   <li>A {@code System.nanoTime()} sample is taken before the method executes.</li>
 *   <li>Duration is computed after the method returns (or throws).</li>
 *   <li>If a {@code MeterRegistry} is available, the duration is recorded as a
 *       {@code Timer} with configurable percentiles.</li>
 *   <li>Slow methods (exceeding the configured threshold) are logged at WARN level.</li>
 * </ul>
 *
 * <p>Usage on a method:
 * <pre>
 *   {@literal @}Timed(value = "checkout.duration", description = "Checkout latency")
 *   public Order checkout(Cart cart) {
 *       // method body is timed
 *   }
 * </pre>
 *
 * <p>Usage on a class (times all public methods):
 * <pre>
 *   {@literal @}Timed
 *   {@literal @}Service
 *   public class PaymentService { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Timed {

    /**
     * Metric name for the timer. Defaults to {@code ClassName.methodName}
     * when empty.
     */
    String value() default "";

    /**
     * Human-readable description of the timer metric, included in the
     * Micrometer {@code Timer} metadata.
     */
    String description() default "";

    /**
     * Percentiles to publish for this timer. Defaults to the 50th, 95th,
     * and 99th percentiles.
     */
    double[] percentiles() default {0.5, 0.95, 0.99};
}
