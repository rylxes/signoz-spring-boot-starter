package io.signoz.springboot.sqs;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Tracing aspect for {@code io.awspring.cloud:spring-cloud-aws-sqs} 3.x —
 * the {@code @SqsListener} annotation in package
 * {@code io.awspring.cloud.sqs.annotation}.
 */
@Aspect
public class AwspringV3SqsListenerTraceAspect extends SqsListenerTraceAspectSupport {

    @Around("@annotation(io.awspring.cloud.sqs.annotation.SqsListener)")
    public Object aroundSqsListener(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp);
    }
}
