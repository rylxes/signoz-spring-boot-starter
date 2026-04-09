package io.signoz.springboot.alerts;

import io.signoz.springboot.annotation.AlertOnFailure;
import io.signoz.springboot.properties.SigNozAlertProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AOP aspect that increments a Micrometer {@code Counter} every time a method
 * annotated with {@link AlertOnFailure} throws an exception.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Proceeds with the target method invocation.</li>
 *   <li>If the method completes normally, returns the result — no counter is touched.</li>
 *   <li>On any {@code Throwable}:
 *       <ul>
 *         <li>If a Micrometer {@code MeterRegistry} is available, increments the
 *             counter identified by {@link AlertOnFailure#metric()} with tags for
 *             class name, method name, exception type, and any additional tags
 *             from {@link AlertOnFailure#tags()}.</li>
 *         <li>Logs a WARN message with class, method, and exception type.</li>
 *         <li>Re-throws the exception.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>The {@code MeterRegistry} dependency is nullable: when Micrometer is not on the
 * classpath (or no registry bean exists), the aspect still logs the failure without
 * recording metrics.
 */
@Aspect
@Order(25)
public class AlertOnFailureAspect {

    private static final Logger log = LoggerFactory.getLogger(AlertOnFailureAspect.class);

    /** Nullable — absent when Micrometer is not on the classpath. */
    private final Object meterRegistry;

    private final SigNozAlertProperties alertProps;

    /**
     * Creates a new {@code AlertOnFailureAspect}.
     *
     * @param meterRegistry a Micrometer {@code MeterRegistry} instance, or {@code null}
     *                      when Micrometer is not available
     * @param alertProps    configuration properties for the alert feature
     */
    public AlertOnFailureAspect(Object meterRegistry, SigNozAlertProperties alertProps) {
        this.meterRegistry = meterRegistry;
        this.alertProps = alertProps;
    }

    /**
     * Intercepts methods annotated with {@link AlertOnFailure}, incrementing a
     * failure counter when the method throws.
     *
     * @param joinPoint      the AOP join point
     * @param alertOnFailure the annotation instance with binding
     * @return the result of the target method
     * @throws Throwable if the target method throws
     */
    @Around("@annotation(alertOnFailure)")
    public Object alertOnFailure(ProceedingJoinPoint joinPoint,
                                  AlertOnFailure alertOnFailure) throws Throwable {
        if (!alertProps.isEnabled()) {
            return joinPoint.proceed();
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            String className = sig.getDeclaringType().getSimpleName();
            String methodName = sig.getName();
            String exceptionType = t.getClass().getSimpleName();

            log.warn("[SigNoz] AlertOnFailure: {}.{} threw {}",
                    className, methodName, exceptionType);

            if (meterRegistry != null) {
                incrementFailureCounter(alertOnFailure, className, methodName, exceptionType);
            }

            throw t;
        }
    }

    /**
     * Increments the Micrometer failure counter with class, method, exception, and
     * any additional annotation-level tags.
     */
    private void incrementFailureCounter(AlertOnFailure alertOnFailure,
                                          String className,
                                          String methodName,
                                          String exceptionType) {
        try {
            io.micrometer.core.instrument.MeterRegistry registry =
                    (io.micrometer.core.instrument.MeterRegistry) meterRegistry;

            List<String> tagList = new ArrayList<String>(Arrays.asList(
                    "class", className,
                    "method", methodName,
                    "exception", exceptionType
            ));

            // Parse additional key=value tags from the annotation
            for (String tag : alertOnFailure.tags()) {
                int eq = tag.indexOf('=');
                if (eq > 0 && eq < tag.length() - 1) {
                    tagList.add(tag.substring(0, eq).trim());
                    tagList.add(tag.substring(eq + 1).trim());
                }
            }

            String[] tagsArray = tagList.toArray(new String[0]);

            io.micrometer.core.instrument.Counter.builder(alertOnFailure.metric())
                    .tags(tagsArray)
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.debug("[SigNoz] Could not record alert counter '{}': {}",
                    alertOnFailure.metric(), e.getMessage());
        }
    }
}
