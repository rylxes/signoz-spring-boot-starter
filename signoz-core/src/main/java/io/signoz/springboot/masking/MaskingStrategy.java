package io.signoz.springboot.masking;

/**
 * Strategy interface for masking a sensitive field value before it is written to logs.
 *
 * <p>Implementations are registered in {@link MaskingRegistry} and selected per field name
 * or via the {@code strategy} attribute on {@link io.signoz.springboot.annotation.Masked}.
 */
public interface MaskingStrategy {

    /**
     * Masks the given raw value.
     *
     * @param fieldName the name of the field (e.g. {@code "password"}, {@code "cardNumber"})
     * @param rawValue  the original string representation; may be {@code null}
     * @return the masked value; never {@code null}
     */
    String mask(String fieldName, String rawValue);
}
