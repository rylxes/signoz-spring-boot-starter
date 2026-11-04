package io.signoz.springboot.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agentless MDC fallback of {@link TraceContextCodec}.
 *
 * <p>{@code TraceIdMdcFilter} writes the OTel-native snake_case keys, while the Kafka,
 * SQS, gRPC and STOMP consumers still write camelCase keys. Outbound propagation must
 * pick up either; before this was fixed it only read camelCase, so a call made while
 * serving an HTTP request carried no {@code traceparent}.
 */
class TraceContextCodecMdcTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void readsSnakeCaseKeysWrittenByTheHttpFilter() {
        MDC.put("trace_id", TRACE_ID);
        MDC.put("span_id", SPAN_ID);
        MDC.put("trace_flags", "00");

        assertThat(TraceContextCodec.currentTraceparent())
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
    }

    @Test
    void fallsBackToLegacyCamelCaseKeys() {
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);

        assertThat(TraceContextCodec.currentTraceparent())
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    void prefersSnakeCaseWhenBothFamiliesArePresent() {
        MDC.put("trace_id", TRACE_ID);
        MDC.put("span_id", SPAN_ID);
        MDC.put("traceId", "11111111111111111111111111111111");
        MDC.put("spanId", "2222222222222222");

        assertThat(TraceContextCodec.currentTraceparent())
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    void neverMixesKeyFamilies() {
        MDC.put("trace_id", TRACE_ID);
        MDC.put("spanId", SPAN_ID);

        assertThat(TraceContextCodec.currentTraceparent()).isNull();
    }

    @Test
    void returnsNullWhenMdcIsEmpty() {
        assertThat(TraceContextCodec.currentTraceparent()).isNull();
    }
}
