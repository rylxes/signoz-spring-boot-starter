package io.signoz.springboot.masking;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Compatibility bean for {@code @Masked} argument handling.
 *
 * <p>Earlier versions replaced annotated arguments before invoking the target
 * method. That changed business input and could break non-String parameters.
 * Masking now happens at log/audit serialization boundaries instead.
 *
 * <p>For manual masking needs, apply masking explicitly using
 * {@link MaskingRegistry}.
 */
@Aspect
@Component
@Order(1)
public class MaskedArgumentAspect {

    public MaskedArgumentAspect(MaskingRegistry maskingRegistry) {
        // Constructor retained for auto-configuration compatibility.
    }
}
