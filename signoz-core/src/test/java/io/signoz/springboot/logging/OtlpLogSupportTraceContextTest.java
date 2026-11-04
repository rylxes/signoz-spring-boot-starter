package io.signoz.springboot.logging;

import io.opentelemetry.api.trace.SpanContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpLogSupportTraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void readsTheSnakeCaseKeys() {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("trace_id", TRACE_ID);
        mdc.put("span_id", SPAN_ID);
        mdc.put("trace_flags", "00");

        SpanContext ctx = OtlpLogSupport.spanContextFromMdc(mdc);

        assertThat(ctx.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(ctx.getSpanId()).isEqualTo(SPAN_ID);
        assertThat(ctx.getTraceFlags().isSampled()).isFalse();
    }

    @Test
    void fallsBackToTheLegacyKeysAndDefaultsToSampled() {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("traceId", TRACE_ID.toUpperCase());
        mdc.put("spanId", SPAN_ID);

        SpanContext ctx = OtlpLogSupport.spanContextFromMdc(mdc);

        assertThat(ctx.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(ctx.getTraceFlags().isSampled()).isTrue();
    }

    @Test
    void ignoresMissingOrInvalidIds() {
        Map<String, String> mdc = new HashMap<String, String>();
        assertThat(OtlpLogSupport.spanContextFromMdc(mdc)).isNull();
        assertThat(OtlpLogSupport.spanContextFromMdc(null)).isNull();

        mdc.put("trace_id", "x");
        mdc.put("span_id", "y");
        assertThat(OtlpLogSupport.spanContextFromMdc(mdc)).isNull();

        mdc.put("trace_id", "00000000000000000000000000000000");
        mdc.put("span_id", SPAN_ID);
        assertThat(OtlpLogSupport.spanContextFromMdc(mdc)).isNull();
    }
}
