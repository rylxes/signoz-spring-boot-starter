package io.signoz.springboot.timed;

import io.signoz.springboot.annotation.Timed;
import io.signoz.springboot.properties.SigNozTimedProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * AOP aspect that records execution time for every method annotated with
 * {@link Timed} (or every public method on a class annotated with {@link Timed}).
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Resolves the {@link Timed} annotation from the method first, then from the
 *       declaring class (method-level takes precedence).</li>
 *   <li>Computes the metric name: {@link Timed#value()} if non-empty, otherwise
 *       {@code ClassName.methodName}.</li>
 *   <li>Records {@code System.nanoTime()} before and after
 *       {@link ProceedingJoinPoint#proceed()}.</li>
 *   <li>Logs the duration at INFO level.</li>
 *   <li>If the duration exceeds {@link SigNozTimedProperties#getSlowThresholdMs()},
 *       logs a WARN with the slow-method message.</li>
 *   <li>If a Micrometer {@code MeterRegistry} is available, records the duration
 *       as a {@code Timer} with percentiles and class/method tags.</li>
 *   <li>On exception the timer is still recorded before the exception is re-thrown.</li>
 * </ol>
 *
 * <p>The {@code MeterRegistry} dependency is nullable: when Micrometer is not on the
 * classpath (or no registry bean exists), the aspect still logs timing information
 * without recording metrics.
 */
@Aspect
@Order(15)
public class TimedAspect {

    private static final Logger log = LoggerFactory.getLogger(TimedAspect.class);

    /** Nullable — absent when Micrometer is not on the classpath. */
    private final Object meterRegistry;

    private final SigNozTimedProperties timedProps;

    /**
     * Creates a new {@code TimedAspect}.
     *
     * @param meterRegistry a Micrometer {@code MeterRegistry} instance, or {@code null}
     *                      when Micrometer is not available
     * @param timedProps    configuration properties for the timed feature
     */
    public TimedAspect(Object meterRegistry, SigNozTimedProperties timedProps) {
        this.meterRegistry = meterRegistry;
        this.timedProps = timedProps;
    }

    /**
     * Intercepts methods and classes annotated with {@link Timed}, recording
     * their execution duration.
     *
     * @param joinPoint the AOP join point
     * @return the result of the target method
     * @throws Throwable if the target method throws
     */
    @Around("@annotation(io.signoz.springboot.annotation.Timed)" +
            " || @within(io.signoz.springboot.annotation.Timed)")
    public Object time(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!timedProps.isEnabled()) {
            return joinPoint.proceed();
        }

        Timed effectiveTimed = resolveAnnotation(joinPoint);

        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String className = sig.getDeclaringType().getSimpleName();
        String methodName = sig.getName();
        String metricName = resolveMetricName(effectiveTimed, className, methodName);

        long startNanos = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long durationNanos = System.nanoTime() - startNanos;
            recordTiming(metricName, effectiveTimed, className, methodName, durationNanos);
            return result;
        } catch (Throwable t) {
            long durationNanos = System.nanoTime() - startNanos;
            recordTiming(metricName, effectiveTimed, className, methodName, durationNanos);
            throw t;
        }
    }

    /**
     * Resolves the {@link Timed} annotation from the method first, then from the
     * declaring class. Returns {@code null} if neither is annotated.
     */
    private Timed resolveAnnotation(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            Method method = sig.getMethod();
            Timed methodLevel = method.getAnnotation(Timed.class);
            if (methodLevel != null) {
                return methodLevel;
            }
            return method.getDeclaringClass().getAnnotation(Timed.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves the metric name: uses the annotation value if non-empty, otherwise
     * falls back to {@code ClassName.methodName}.
     */
    private String resolveMetricName(Timed timed, String className, String methodName) {
        if (timed != null && !timed.value().isEmpty()) {
            return timed.value();
        }
        return className + "." + methodName;
    }

    /**
     * Logs the timing information and records it in the Micrometer registry if available.
     */
    private void recordTiming(String metricName, Timed timed,
                              String className, String methodName,
                              long durationNanos) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);

        log.info("[SigNoz] Timed {} completed in {}ms", metricName, durationMs);

        if (durationMs > timedProps.getSlowThresholdMs()) {
            log.warn("[SigNoz] SLOW method {} took {}ms (threshold: {}ms)",
                    metricName, durationMs, timedProps.getSlowThresholdMs());
        }

        if (meterRegistry != null) {
            recordMicrometerTimer(metricName, timed, className, methodName, durationNanos);
        }
    }

    /**
     * Records a Micrometer {@code Timer} sample. Uses reflection-free calls
     * since micrometer-core is an optional compile dependency of signoz-core.
     */
    private void recordMicrometerTimer(String metricName, Timed timed,
                                       String className, String methodName,
                                       long durationNanos) {
        try {
            io.micrometer.core.instrument.MeterRegistry registry =
                    (io.micrometer.core.instrument.MeterRegistry) meterRegistry;

            double[] percentiles = (timed != null) ? timed.percentiles() : new double[]{0.5, 0.95, 0.99};
            String description = (timed != null) ? timed.description() : "";

            io.micrometer.core.instrument.Timer.Builder builder =
                    io.micrometer.core.instrument.Timer.builder(metricName)
                            .description(description)
                            .publishPercentiles(percentiles)
                            .tag("class", className)
                            .tag("method", methodName);

            builder.register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            log.debug("[SigNoz] Could not record timer metric '{}': {}",
                    metricName, e.getMessage());
        }
    }
}
