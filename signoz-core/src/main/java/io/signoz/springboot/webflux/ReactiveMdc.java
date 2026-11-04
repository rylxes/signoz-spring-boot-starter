package io.signoz.springboot.webflux;

import io.signoz.springboot.tracing.TraceContextCodec;
import io.signoz.springboot.tracing.TraceIdentity;
import io.signoz.springboot.usercontext.UserContextEnricher;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Signal;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Carries the SigNoz MDC entries (request ID, trace IDs, user context) through a
 * reactive request, where thread-local MDC alone does not survive thread hops.
 *
 * <p>The WebFilters store the entries in the Reactor {@link Context} under
 * {@link #CONTEXT_KEY}, and also put them on the thread while the filter chain is
 * being subscribed, so a handler's logs carry them up to its first asynchronous
 * boundary. Past that boundary:
 *
 * <ul>
 *   <li>On Spring Boot 3 the starter registers a context-propagation
 *       {@code ThreadLocalAccessor} for {@link #CONTEXT_KEY} and enables Reactor's
 *       automatic context propagation, so the MDC is restored on every hop.</li>
 *   <li>Everywhere else, use {@link #onNext(Consumer)} or
 *       {@link #runWithMdc(ContextView, Runnable)} around the log call.</li>
 * </ul>
 */
public final class ReactiveMdc {

    /** Reactor {@link Context} key holding an unmodifiable {@code Map<String, String>}. */
    public static final String CONTEXT_KEY = "io.signoz.springboot.mdc";

    /** Every MDC key the reactive filters may write, used to clear them again. */
    public static final List<String> KNOWN_KEYS = Collections.unmodifiableList(Arrays.asList(
            TraceIdentity.MDC_REQUEST_ID,
            TraceContextCodec.MDC_TRACE_ID,
            TraceContextCodec.MDC_SPAN_ID,
            TraceContextCodec.MDC_TRACE_FLAGS,
            UserContextEnricher.MDC_USER_ID,
            UserContextEnricher.MDC_USER_EMAIL,
            UserContextEnricher.MDC_USER_ROLES));

    private ReactiveMdc() {
        // utility class
    }

    /** The SigNoz MDC entries in {@code context}, or an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> fromContext(ContextView context) {
        Object value = context.getOrDefault(CONTEXT_KEY, null);
        return value instanceof Map ? (Map<String, String>) value : Collections.<String, String>emptyMap();
    }

    /** {@code context} with {@code entries} merged over any entries already present. */
    public static Context withEntries(Context context, Map<String, String> entries) {
        Map<String, String> merged = new LinkedHashMap<String, String>(fromContext(context));
        merged.putAll(entries);
        return context.put(CONTEXT_KEY, Collections.unmodifiableMap(merged));
    }

    /**
     * Run {@code action} with the context's SigNoz entries in the MDC, restoring the
     * previous MDC afterwards.
     */
    public static void runWithMdc(ContextView context, Runnable action) {
        runWithMdc(fromContext(context), action);
    }

    /** Run {@code action} with {@code entries} in the MDC, restoring the previous MDC afterwards. */
    public static void runWithMdc(Map<String, String> entries, Runnable action) {
        Map<String, String> previous = apply(entries);
        try {
            action.run();
        } finally {
            restore(entries, previous);
        }
    }

    /**
     * A {@code doOnEach} callback that runs {@code consumer} for each value with the
     * SigNoz MDC entries in place:
     * {@code mono.doOnEach(ReactiveMdc.onNext(v -> log.info("got {}", v)))}.
     */
    public static <T> Consumer<Signal<T>> onNext(final Consumer<T> consumer) {
        return new Consumer<Signal<T>>() {
            @Override
            public void accept(final Signal<T> signal) {
                if (!signal.isOnNext()) {
                    return;
                }
                runWithMdc(signal.getContextView(), new Runnable() {
                    @Override
                    public void run() {
                        consumer.accept(signal.get());
                    }
                });
            }
        };
    }

    /**
     * A {@code Mono} that, when subscribed, puts {@code entries} in the MDC, obtains
     * the downstream publisher from {@code source} and subscribes to it with the
     * entries also stored in the Reactor context, then restores the MDC. Synchronous
     * work done at that point (most of a typical handler) therefore logs with them,
     * whether or not the filter chain defers its own invocation.
     */
    static Mono<Void> scoped(final Supplier<Mono<Void>> source, final Map<String, String> entries) {
        return new Mono<Void>() {
            @Override
            public void subscribe(CoreSubscriber<? super Void> actual) {
                Map<String, String> previous = apply(entries);
                try {
                    Mono<Void> downstream;
                    try {
                        downstream = source.get();
                    } catch (Throwable t) {
                        Operators.error(actual, Operators.onOperatorError(t, actual.currentContext()));
                        return;
                    }
                    downstream.contextWrite(ctx -> withEntries(ctx, entries)).subscribe(actual);
                } finally {
                    restore(entries, previous);
                }
            }
        };
    }

    /** Put {@code entries} in the MDC; returns the values they replaced (nulls included). */
    private static Map<String, String> apply(Map<String, String> entries) {
        Map<String, String> previous = new HashMap<String, String>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            previous.put(e.getKey(), MDC.get(e.getKey()));
            if (e.getValue() != null) {
                MDC.put(e.getKey(), e.getValue());
            }
        }
        return previous;
    }

    private static void restore(Map<String, String> entries, Map<String, String> previous) {
        for (String key : entries.keySet()) {
            String old = previous.get(key);
            if (old == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, old);
            }
        }
    }
}
