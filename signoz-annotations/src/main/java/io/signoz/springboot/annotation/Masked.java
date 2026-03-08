package io.signoz.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter, field, or return value as sensitive so that it
 * is redacted before being written to any log output.
 *
 * <p>The masking is applied by:
 * <ol>
 *   <li>{@code MaskedArgumentAspect} — intercepts bean method calls and replaces the
 *       annotated argument's {@code toString()} representation before it reaches the logger.</li>
 *   <li>{@code SigNozJsonEncoder} — scans JSON log output for field names matching
 *       {@code signoz.logging.masked-fields} and replaces their values.</li>
 * </ol>
 *
 * <p>Usage on a method parameter:
 * <pre>
 *   public void login(String username, {@literal @}Masked String password) {
 *       log.info("Login attempt for {}", username); // password not logged
 *   }
 * </pre>
 *
 * <p>Usage on a field (masks the field value when the object is serialised into a log):
 * <pre>
 *   public class PaymentRequest {
 *       {@literal @}Masked(strategy = MaskingStrategy.PARTIAL)
 *       private String cardNumber;
 *   }
 * </pre>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Masked {

    /**
     * The masking strategy to apply.
     */
    MaskingStrategy strategy() default MaskingStrategy.FULL;

    /**
     * Custom replacement string used when {@link MaskingStrategy#FULL} is chosen.
     * Defaults to {@code "***"}.
     */
    String replacement() default "***";

    /**
     * Custom regex pattern used when {@link MaskingStrategy#REGEX} is chosen.
     * The entire match is replaced with {@link #replacement()}.
     */
    String pattern() default "";

    /**
     * Masking strategies available for {@code @Masked}.
     */
    enum MaskingStrategy {
        /** Replace the entire value with {@link Masked#replacement()} (default). */
        FULL,
        /** Show first 2 and last 2 characters; mask the rest with {@code *}. */
        PARTIAL,
        /** Apply a custom regex defined in {@link Masked#pattern()}. */
        REGEX
    }
}
