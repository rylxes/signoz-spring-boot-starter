package io.signoz.springboot.async;

import org.slf4j.MDC;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link ExecutorService} wrapper that propagates the SLF4J {@link MDC} context
 * to every submitted task.
 *
 * <p>Each {@link Runnable} or {@link Callable} is decorated so that the MDC snapshot
 * from the calling thread is restored in the worker thread before the task executes,
 * and cleared after it completes.
 *
 * <p>All other {@code ExecutorService} methods delegate to the underlying service.
 */
public class TracingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    /**
     * Creates a new tracing executor service wrapping the given delegate.
     *
     * @param delegate the underlying executor service to delegate to
     */
    public TracingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrapRunnable(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrapRunnable(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrapRunnable(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrapCallable(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(wrapCallables(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(wrapCallables(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapCallables(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapCallables(tasks), timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /**
     * Wraps a {@link Runnable} to capture and restore the current MDC context.
     */
    private Runnable wrapRunnable(final Runnable task) {
        final Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return new Runnable() {
            @Override
            public void run() {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wraps a {@link Callable} to capture and restore the current MDC context.
     */
    private <T> Callable<T> wrapCallable(final Callable<T> task) {
        final Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return new Callable<T>() {
            @Override
            public T call() throws Exception {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    return task.call();
                } finally {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * Wraps a collection of callables with MDC context propagation.
     */
    private <T> Collection<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<Callable<T>>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrapCallable(task));
        }
        return wrapped;
    }
}
