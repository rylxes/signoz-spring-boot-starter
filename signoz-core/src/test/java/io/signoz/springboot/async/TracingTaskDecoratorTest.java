package io.signoz.springboot.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TracingTaskDecorator}.
 */
class TracingTaskDecoratorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void mdcContextIsPropagatedToDecoratedRunnable() throws InterruptedException {
        MDC.put("traceId", "abc123");
        MDC.put("userId", "testUser");

        TracingTaskDecorator decorator = new TracingTaskDecorator();

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedUserId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable original = new Runnable() {
            @Override
            public void run() {
                capturedTraceId.set(MDC.get("traceId"));
                capturedUserId.set(MDC.get("userId"));
                latch.countDown();
            }
        };

        Runnable decorated = decorator.decorate(original);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.execute(decorated);
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(capturedTraceId.get()).isEqualTo("abc123");
            assertThat(capturedUserId.get()).isEqualTo("testUser");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void mdcIsClearedAfterDecoratedRunnableCompletes() throws InterruptedException {
        MDC.put("traceId", "abc123");

        TracingTaskDecorator decorator = new TracingTaskDecorator();

        AtomicReference<Map<String, String>> mdcAfterRun = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        // First task: sets MDC, then we check if second task on same thread has clean MDC
        Runnable first = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                // MDC should be set here
                latch.countDown();
            }
        });

        // Clear parent MDC before creating second task (to test isolation)
        MDC.clear();

        Runnable second = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                mdcAfterRun.set(MDC.getCopyOfContextMap());
                latch.countDown();
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.execute(first);
            executor.execute(second);
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            // Second task should have null/empty MDC since parent had no MDC when it was created
            Map<String, String> result = mdcAfterRun.get();
            assertThat(result == null || result.isEmpty()).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void decorateHandlesNullMdcContext() throws InterruptedException {
        MDC.clear();

        TracingTaskDecorator decorator = new TracingTaskDecorator();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> taskRan = new AtomicReference<>(false);

        Runnable decorated = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                taskRan.set(true);
                latch.countDown();
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.execute(decorated);
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(taskRan.get()).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void multipleKeysArePropagated() throws InterruptedException {
        Map<String, String> expected = new HashMap<String, String>();
        expected.put("traceId", "trace-1");
        expected.put("spanId", "span-1");
        expected.put("userId", "user-1");
        expected.put("requestId", "req-1");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            MDC.put(entry.getKey(), entry.getValue());
        }

        TracingTaskDecorator decorator = new TracingTaskDecorator();
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable decorated = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                captured.set(MDC.getCopyOfContextMap());
                latch.countDown();
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.execute(decorated);
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(captured.get()).containsAllEntriesOf(expected);
        } finally {
            executor.shutdown();
        }
    }
}
