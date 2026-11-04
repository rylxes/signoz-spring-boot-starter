package io.signoz.e2e.support;

import java.util.function.Supplier;

/** Polls until a value appears, or fails with a description of what was awaited. */
public final class Await {

    private Await() {
    }

    /** Returns the first non-null, non-false value from {@code probe}, polling every 100ms. */
    public static <T> T until(Supplier<T> probe, long timeoutMs, String description) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeException last = null;
        while (true) {
            try {
                T value = probe.get();
                if (value != null && !Boolean.FALSE.equals(value)) {
                    return value;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            if (System.currentTimeMillis() > deadline) {
                AssertionError error = new AssertionError("Timed out after " + timeoutMs + "ms waiting for: " + description);
                if (last != null) {
                    error.initCause(last);
                }
                throw error;
            }
            ServiceProcess.sleep(100);
        }
    }
}
