package io.signoz.e2e.app.common;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the service saw when work arrived on a channel (Kafka, SQS, STOMP, @Async): the MDC at
 * that moment plus details. Tests read it back through {@code GET /e2e/last/{channel}}.
 */
@Component
public class Observations {

    private final Map<String, Map<String, String>> last = new ConcurrentHashMap<String, Map<String, String>>();

    public void record(String channel, Map<String, String> details) {
        Map<String, String> entry = new LinkedHashMap<String, String>(mdc());
        entry.putAll(details);
        entry.put("thread", Thread.currentThread().getName());
        last.put(channel, entry);
    }

    public Map<String, String> last(String channel) {
        Map<String, String> entry = last.get(channel);
        return entry != null ? entry : Collections.<String, String>emptyMap();
    }

    /** The current MDC, never null. */
    public static Map<String, String> mdc() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy != null ? new LinkedHashMap<String, String>(copy) : new LinkedHashMap<String, String>();
    }
}
