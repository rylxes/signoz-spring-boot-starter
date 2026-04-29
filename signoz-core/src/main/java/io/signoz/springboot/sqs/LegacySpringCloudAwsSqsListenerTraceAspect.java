package io.signoz.springboot.sqs;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Tracing aspect for legacy {@code spring-cloud-starter-aws-messaging} —
 * the {@code @SqsListener} annotation in package
 * {@code org.springframework.cloud.aws.messaging.listener.annotation}.
 */
@Aspect
public class LegacySpringCloudAwsSqsListenerTraceAspect extends SqsListenerTraceAspectSupport {

    @Around("@annotation(org.springframework.cloud.aws.messaging.listener.annotation.SqsListener)")
    public Object aroundSqsListener(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp);
    }
}
