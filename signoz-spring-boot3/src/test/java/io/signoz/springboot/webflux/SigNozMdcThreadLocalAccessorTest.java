package io.signoz.springboot.webflux;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SigNozMdcThreadLocalAccessorTest {

    private final SigNozMdcThreadLocalAccessor accessor = new SigNozMdcThreadLocalAccessor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void setValueReplacesRatherThanMergesSoNoUserLeaksAcrossRequests() {
        // Request A's MDC is still on the thread when a signal for anonymous request B runs.
        MDC.put("userId", "alice");
        MDC.put("requestId", "req-a");
        Map<String, String> previous = accessor.getValue();

        accessor.setValue(Collections.singletonMap("requestId", "req-b"));

        assertThat(MDC.get("requestId")).isEqualTo("req-b");
        assertThat(MDC.get("userId")).isNull();

        accessor.restore(previous);

        assertThat(MDC.get("requestId")).isEqualTo("req-a");
        assertThat(MDC.get("userId")).isEqualTo("alice");
    }

    @Test
    void clearingTouchesOnlySigNozKeys() {
        MDC.put("requestId", "req-a");
        MDC.put("tenant", "acme");

        accessor.setValue();

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("tenant")).isEqualTo("acme");
    }

    @Test
    void getValueIsNullWhenNoSigNozKeysArePresent() {
        MDC.put("tenant", "acme");
        assertThat(accessor.getValue()).isNull();
    }

    @Test
    void toleratesNullValues() {
        MDC.put("userId", "alice");
        accessor.setValue(null);
        accessor.restore(null);
        assertThat(MDC.get("userId")).isNull();
    }
}
