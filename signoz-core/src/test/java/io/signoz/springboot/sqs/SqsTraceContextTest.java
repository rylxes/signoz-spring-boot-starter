package io.signoz.springboot.sqs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqsTraceContextTest {

    @AfterEach
    void clearMdc() {
        SqsTraceContext.clearMdc();
    }

    @Test
    void populateMdcParsesTraceparentAndSetsAllKeys() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        headers.put("X-Request-ID", "req-123");

        boolean populated = SqsTraceContext.populateMdcFromHeaders(headers);

        assertThat(populated).isTrue();
        assertThat(MDC.get("traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(MDC.get("spanId")).isEqualTo("b7ad6b7169203331");
        assertThat(MDC.get("traceFlags")).isEqualTo("01");
        assertThat(MDC.get("requestId")).isEqualTo("req-123");
    }

    @Test
    void populateMdcDefaultsRequestIdToTraceIdWhenAbsent() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        SqsTraceContext.populateMdcFromHeaders(headers);

        assertThat(MDC.get("requestId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void populateMdcReturnsFalseOnMalformedTraceparent() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "garbage");

        assertThat(SqsTraceContext.populateMdcFromHeaders(headers)).isFalse();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void populateMdcReturnsFalseOnEmptyOrNull() {
        assertThat(SqsTraceContext.populateMdcFromHeaders(null)).isFalse();
        assertThat(SqsTraceContext.populateMdcFromHeaders(new HashMap<>())).isFalse();
    }

    @Test
    void clearMdcRemovesOnlyTracingKeys() {
        MDC.put("traceId", "x");
        MDC.put("spanId", "y");
        MDC.put("traceFlags", "01");
        MDC.put("requestId", "z");
        MDC.put("unrelated", "keep-me");

        SqsTraceContext.clearMdc();

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
        assertThat(MDC.get("traceFlags")).isNull();
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("keep-me");

        MDC.remove("unrelated");
    }
}
