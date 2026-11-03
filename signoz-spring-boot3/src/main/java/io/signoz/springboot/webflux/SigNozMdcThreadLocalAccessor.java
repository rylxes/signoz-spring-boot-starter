package io.signoz.springboot.webflux;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges the SigNoz MDC entries that the reactive filters keep in the Reactor context
 * (under {@link ReactiveMdc#CONTEXT_KEY}) to the thread-local SLF4J MDC.
 *
 * <p>With Reactor's automatic context propagation enabled, Reactor calls
 * {@link #setValue(Map)} with the context's entries whenever it runs an operator on a
 * thread, and {@link #restore(Map)} afterwards, so logs written anywhere in a request's
 * reactive pipeline carry its {@code requestId}, trace IDs and user context.
 *
 * <p>Only the keys in {@link ReactiveMdc#KNOWN_KEYS} are ever touched.
 */
public class SigNozMdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    @Override
    public Object key() {
        return ReactiveMdc.CONTEXT_KEY;
    }

    @Override
    public Map<String, String> getValue() {
        Map<String, String> current = new LinkedHashMap<>();
        for (String key : ReactiveMdc.KNOWN_KEYS) {
            String value = MDC.get(key);
            if (value != null) {
                current.put(key, value);
            }
        }
        return current.isEmpty() ? null : Collections.unmodifiableMap(current);
    }

    @Override
    public void setValue(Map<String, String> value) {
        for (Map.Entry<String, String> e : value.entrySet()) {
            if (e.getValue() != null) {
                MDC.put(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public void setValue() {
        for (String key : ReactiveMdc.KNOWN_KEYS) {
            MDC.remove(key);
        }
    }

    @Override
    public void restore(Map<String, String> previousValue) {
        setValue();
        setValue(previousValue);
    }

    @Override
    public void restore() {
        setValue();
    }
}
