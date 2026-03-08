package io.signoz.springboot.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.signoz.springboot.annotation.Traced;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * AOP aspect that creates an OpenTelemetry span around every method annotated
 * with {@link Traced} (or every public method on a class annotated with {@link Traced}).
 *
 * <p>The span lifecycle:
 * <ol>
 *   <li>Span created and made current (injected into MDC via {@code TraceIdMdcFilter})</li>
 *   <li>Method executes within the span context</li>
 *   <li>On exception: exception recorded, span status set to {@code ERROR}</li>
 *   <li>Span ended unconditionally in {@code finally}</li>
 * </ol>
 *
 * <p>Tags from {@link Traced#tags()} are added as span attributes.
 * Tags must be in {@code "key=value"} format; malformed tags are silently skipped.
 */
@Aspect
@Component
@Order(10)
public class TracedAspect {

    private static final Logger log = LoggerFactory.getLogger(TracedAspect.class);

    private final SigNozTracer sigNozTracer;

    public TracedAspect(SigNozTracer sigNozTracer) {
        this.sigNozTracer = sigNozTracer;
    }

    @Around("@annotation(io.signoz.springboot.annotation.Traced)" +
            " || @within(io.signoz.springboot.annotation.Traced)")
    public Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
        // Resolve annotation from method-level first, then class-level.
        Traced effectiveTraced = resolveAnnotation(joinPoint);

        String operationName = resolveOperationName(joinPoint, effectiveTraced);
        Span span = sigNozTracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        // Add custom tags
        applyTags(span, effectiveTraced);

        Scope scope = span.makeCurrent();
        try {
            Object result = joinPoint.proceed();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Throwable t) {
            if (effectiveTraced != null && effectiveTraced.recordException()) {
                span.recordException(t);
                span.setStatus(StatusCode.ERROR, t.getMessage());
            }
            throw t;
        } finally {
            scope.close();
            span.end();
        }
    }

    private Traced resolveAnnotation(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            Method method = sig.getMethod();
            Traced methodLevel = method.getAnnotation(Traced.class);
            if (methodLevel != null) return methodLevel;
            return method.getDeclaringClass().getAnnotation(Traced.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveOperationName(ProceedingJoinPoint joinPoint, Traced traced) {
        if (traced != null && !traced.operationName().isEmpty()) {
            return traced.operationName();
        }
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        return sig.getDeclaringTypeName() + "." + sig.getName();
    }

    private void applyTags(Span span, Traced traced) {
        if (traced == null) return;
        for (String tag : traced.tags()) {
            int eq = tag.indexOf('=');
            if (eq > 0 && eq < tag.length() - 1) {
                String key = tag.substring(0, eq).trim();
                String value = tag.substring(eq + 1).trim();
                span.setAttribute(AttributeKey.stringKey(key), value);
            }
        }
    }
}
