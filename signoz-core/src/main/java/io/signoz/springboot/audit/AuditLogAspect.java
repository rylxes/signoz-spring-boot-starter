package io.signoz.springboot.audit;

import io.opentelemetry.api.trace.Span;
import io.signoz.springboot.annotation.AuditLog;
import io.signoz.springboot.properties.SigNozAuditProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;

/**
 * AOP aspect that captures audit trail entries for all methods annotated with
 * {@link AuditLog}.
 *
 * <p>For each method invocation an {@link AuditEvent} is built containing:
 * <ul>
 *   <li>Current trace/span IDs from the active OpenTelemetry span</li>
 *   <li>Actor extracted from Spring Security's {@code SecurityContextHolder}
 *       (falls back to {@code "anonymous"} when Security is not present)</li>
 *   <li>Method arguments (when {@code captureArgs=true})</li>
 *   <li>Return value (when {@code captureResult=true})</li>
 *   <li>Exception details (outcome set to {@code FAILURE})</li>
 * </ul>
 *
 * <p>The event is then published via {@link ApplicationEventPublisher}. Register
 * additional {@code @EventListener(AuditEvent.class)} beans to persist audit
 * entries to a database, message queue, or external system.
 */
@Aspect
@Component
@Order(20)
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final ApplicationEventPublisher eventPublisher;
    private final SigNozAuditProperties auditProps;

    public AuditLogAspect(ApplicationEventPublisher eventPublisher,
                          SigNozAuditProperties auditProps) {
        this.eventPublisher = eventPublisher;
        this.auditProps = auditProps;
    }

    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        if (!auditProps.isEnabled()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Instant startTime = Instant.now();

        // Gather trace context
        Span currentSpan = Span.current();
        String traceId = currentSpan.getSpanContext().getTraceId();
        String spanId = currentSpan.getSpanContext().getSpanId();

        // Extract actor (Spring Security optional)
        String actor = extractActor();

        // Capture args if enabled
        boolean captureArgs = auditLog.captureArgs() && auditProps.isCaptureArgs();
        Object[] capturedArgs = captureArgs ? joinPoint.getArgs() : null;

        try {
            Object result = joinPoint.proceed();

            // Capture result if enabled
            boolean captureResult = auditLog.captureResult() && auditProps.isCaptureResult();
            Object capturedResult = captureResult ? result : null;

            AuditEvent event = AuditEvent.builder()
                    .traceId(traceId)
                    .spanId(spanId)
                    .actor(actor)
                    .action(auditLog.action())
                    .resourceType(auditLog.resourceType())
                    .resourceId(resolveResourceId(auditLog, joinPoint))
                    .args(capturedArgs)
                    .result(capturedResult)
                    .outcome(AuditEvent.Outcome.SUCCESS)
                    .timestamp(startTime)
                    .thread(auditProps.isIncludeThread() ? Thread.currentThread().getName() : null)
                    .className(signature.getDeclaringTypeName())
                    .methodName(method.getName())
                    .build();

            eventPublisher.publishEvent(event);
            return result;

        } catch (Throwable t) {
            AuditEvent event = AuditEvent.builder()
                    .traceId(traceId)
                    .spanId(spanId)
                    .actor(actor)
                    .action(auditLog.action())
                    .resourceType(auditLog.resourceType())
                    .resourceId(resolveResourceId(auditLog, joinPoint))
                    .args(capturedArgs)
                    .exception(t)
                    .outcome(AuditEvent.Outcome.FAILURE)
                    .timestamp(startTime)
                    .thread(auditProps.isIncludeThread() ? Thread.currentThread().getName() : null)
                    .className(signature.getDeclaringTypeName())
                    .methodName(method.getName())
                    .build();

            eventPublisher.publishEvent(event);
            throw t;
        }
    }

    /**
     * Extracts the current actor from Spring Security (if on classpath).
     * Returns {@code "anonymous"} as a safe fallback.
     */
    private String extractActor() {
        try {
            Class<?> holderClass = Class.forName(
                    "org.springframework.security.core.context.SecurityContextHolder");
            Object context = holderClass.getMethod("getContext").invoke(null);
            if (context != null) {
                Object authentication = context.getClass()
                        .getMethod("getAuthentication").invoke(context);
                if (authentication != null) {
                    Object principal = authentication.getClass()
                            .getMethod("getName").invoke(authentication);
                    if (principal != null) {
                        return principal.toString();
                    }
                }
            }
        } catch (Exception ignored) {
            // Spring Security not present or unauthenticated
        }
        return "anonymous";
    }

    /**
     * Resolves the resource ID from the SpEL expression in {@link AuditLog#resourceId()}.
     * Returns empty string if no expression is defined or evaluation fails.
     */
    private String resolveResourceId(AuditLog auditLog, ProceedingJoinPoint joinPoint) {
        String expression = auditLog.resourceId();
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        try {
            // Simple SpEL evaluation via Spring Expression if available
            Class<?> parserClass = Class.forName(
                    "org.springframework.expression.spel.standard.SpelExpressionParser");
            Object parser = parserClass.newInstance();
            Object expr = parserClass.getMethod("parseExpression", String.class)
                    .invoke(parser, expression);

            // Build evaluation context with method args
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = sig.getParameterNames();
            Object[] args = joinPoint.getArgs();

            Class<?> contextClass = Class.forName(
                    "org.springframework.expression.spel.support.StandardEvaluationContext");
            Object evalContext = contextClass.newInstance();

            if (paramNames != null) {
                Method setVar = contextClass.getMethod("setVariable", String.class, Object.class);
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    setVar.invoke(evalContext, paramNames[i], args[i]);
                }
            }

            Object result = expr.getClass()
                    .getMethod("getValue",
                            Class.forName("org.springframework.expression.EvaluationContext"))
                    .invoke(expr, evalContext);
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.debug("[SigNoz] Could not resolve resourceId expression '{}': {}",
                    expression, e.getMessage());
            return "";
        }
    }
}
