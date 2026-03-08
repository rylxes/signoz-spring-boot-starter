package io.signoz.springboot.masking;

import io.signoz.springboot.annotation.Masked;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * AOP aspect that intercepts bean method calls and replaces {@code @Masked}-annotated
 * arguments with their masked representation before the method executes.
 *
 * <p>This ensures that even if the called method logs its arguments internally,
 * the sensitive values are never exposed.
 *
 * <p>Only applies to Spring-managed beans (AOP proxy scope). For non-managed
 * objects, apply masking explicitly using {@link MaskingRegistry}.
 *
 * <p>This aspect runs at high priority ({@code @Order(1)}) so masking happens
 * before any other advice (e.g. tracing, auditing) that might log arguments.
 */
@Aspect
@Component
@Order(1)
public class MaskedArgumentAspect {

    private static final Logger log = LoggerFactory.getLogger(MaskedArgumentAspect.class);

    private final MaskingRegistry maskingRegistry;

    public MaskedArgumentAspect(MaskingRegistry maskingRegistry) {
        this.maskingRegistry = maskingRegistry;
    }

    /**
     * Intercept all public methods on Spring beans where at least one parameter
     * has the {@code @Masked} annotation.
     */
    @Around("execution(public * *(..)) && @within(org.springframework.stereotype.Component)")
    public Object maskArguments(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        boolean hasMasked = false;
        for (Annotation[] annotations : paramAnnotations) {
            for (Annotation ann : annotations) {
                if (ann instanceof Masked) {
                    hasMasked = true;
                    break;
                }
            }
            if (hasMasked) break;
        }

        if (!hasMasked) {
            return joinPoint.proceed(args);
        }

        Object[] maskedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            maskedArgs[i] = args[i];
            for (Annotation ann : paramAnnotations[i]) {
                if (ann instanceof Masked) {
                    Masked masked = (Masked) ann;
                    if (args[i] != null) {
                        maskedArgs[i] = applyMask(
                                paramNames != null ? paramNames[i] : "param" + i,
                                args[i],
                                masked);
                    }
                    break;
                }
            }
        }

        return joinPoint.proceed(maskedArgs);
    }

    private Object applyMask(String paramName, Object value, Masked masked) {
        String rawString = value.toString();
        switch (masked.strategy()) {
            case PARTIAL:
                return new PartialMaskingStrategy().mask(paramName, rawString);
            case REGEX:
                if (!masked.pattern().isEmpty()) {
                    return new RegexMaskingStrategy(masked.pattern(), masked.replacement())
                            .mask(paramName, rawString);
                }
                // Fall through to FULL if no pattern provided
            case FULL:
            default:
                return masked.replacement();
        }
    }
}
