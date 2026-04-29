package io.signoz.springboot.sqs;

import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;

import java.util.Map;

/**
 * Shared base for the per-flavor {@code @SqsListener} aspects. Each concrete
 * subclass declares an AspectJ pointcut against one specific annotation
 * package it targets, and the auto-configuration only registers the subclass
 * when its annotation type is present on the classpath.
 *
 * <p>Why three subclasses instead of one combined pointcut? Spring's
 * {@code AspectJExpressionPointcut} resolves every {@code @annotation(FQN)}
 * reference eagerly via {@code Class.forName(...)}; a missing class fails
 * bean initialization. Splitting by flavor lets {@code @ConditionalOnClass}
 * skip the bean cleanly when its annotation isn't on the classpath.
 *
 * <p>Behaviour: extract W3C {@code traceparent} from the listener's inbound
 * message headers ({@link Message} / {@link MessageHeaders} / {@link Map}
 * arguments) into MDC, run the listener, then clear MDC.
 */
abstract class SqsListenerTraceAspectSupport {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_SQS");

    Object trace(ProceedingJoinPoint pjp) throws Throwable {
        boolean populated = populateMdcFrom(pjp.getArgs());
        try {
            return pjp.proceed();
        } finally {
            if (populated) {
                SqsTraceContext.clearMdc();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean populateMdcFrom(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof Message<?>) {
                MessageHeaders headers = ((Message<?>) arg).getHeaders();
                if (SqsTraceContext.populateMdcFromHeaders(headers)) {
                    return true;
                }
            } else if (arg instanceof MessageHeaders) {
                if (SqsTraceContext.populateMdcFromHeaders((MessageHeaders) arg)) {
                    return true;
                }
            } else if (arg instanceof Map) {
                try {
                    if (SqsTraceContext.populateMdcFromHeaders((Map<String, ?>) arg)) {
                        return true;
                    }
                } catch (ClassCastException ignore) {
                    // Not a String-keyed map; skip silently.
                }
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("[SigNoz] @SqsListener method has no Message/Headers/Map argument — "
                    + "trace context not auto-extracted");
        }
        return false;
    }
}
