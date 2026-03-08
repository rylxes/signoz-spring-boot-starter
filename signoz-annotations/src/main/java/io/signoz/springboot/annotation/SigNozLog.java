package io.signoz.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a private static final SLF4J {@code Logger} field named {@code log}
 * into the annotated class at compile time, equivalent to Lombok's {@code @Slf4j}.
 *
 * <p>The generated field:
 * <pre>
 *   private static final org.slf4j.Logger log =
 *       org.slf4j.LoggerFactory.getLogger(AnnotatedClass.class);
 * </pre>
 *
 * <p>All log statements written through this field automatically benefit from:
 * <ul>
 *   <li>Trace/span ID injection via MDC</li>
 *   <li>Sensitive field masking configured in {@code signoz.logging.masked-fields}</li>
 *   <li>OTLP/JSON output routing configured in {@code signoz.logging.mode}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}SigNozLog
 *   {@literal @}Service
 *   public class OrderService {
 *       public void create(Order order) {
 *           log.info("Creating order {}", order.getId()); // log injected
 *       }
 *   }
 * </pre>
 *
 * <p>Requires {@code signoz-annotation-processor} on the compile classpath
 * (included transitively by the starter).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface SigNozLog {
    /**
     * Custom topic/name for the logger. Defaults to the annotated class name.
     * When set, the generated field will use {@code LoggerFactory.getLogger(topic())}.
     */
    String topic() default "";
}
