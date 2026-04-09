package io.signoz.springboot.async;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * A {@link TaskDecorator} that propagates the SLF4J {@link MDC} context from the
 * calling thread to the async worker thread.
 *
 * <p>This ensures that trace IDs, user context, and any other MDC entries set by
 * upstream filters are available in {@code @Async} methods and thread-pool tasks.
 *
 * <p>Usage: register this decorator on a {@code ThreadPoolTaskExecutor} via
 * {@code executor.setTaskDecorator(new TracingTaskDecorator())}.
 */
public class TracingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return new Runnable() {
            @Override
            public void run() {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            }
        };
    }
}
