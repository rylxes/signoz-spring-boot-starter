package io.signoz.springboot.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the MDC keys {@link TraceIdMdcFilter} writes.
 *
 * <p>Trace correlation uses OTel-native snake_case keys and defers to the agent
 * when a valid span is already current; {@code requestId} is always present.
 */
class TraceIdMdcFilterMdcTest {

    private final TraceIdMdcFilter filter = new TraceIdMdcFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private Map<String, String> mdcSeenByChain(MockHttpServletRequest request,
                                               MockHttpServletResponse response) throws Exception {
        Map<String, String> seen = new HashMap<>();
        FilterChain chain = (rq, rs) -> {
            for (String key : new String[]{
                    "trace_id", "span_id", "trace_flags", "requestId", "traceId", "spanId", "traceFlags"}) {
                seen.put(key, MDC.get(key));
            }
        };
        filter.doFilter(request, response, chain);
        return seen;
    }

    @Test
    public void agentlessRequestUsesSnakeCaseTraceKeysAndRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> seen = mdcSeenByChain(request, response);

        assertThat(seen.get("trace_id")).isNotNull().hasSize(32);
        assertThat(seen.get("span_id")).isNotNull().hasSize(16);
        assertThat(seen.get("trace_flags")).isNotNull();
        assertThat(seen.get("requestId")).isNotNull();

        assertThat(seen.get("traceId")).isNull();
        assertThat(seen.get("spanId")).isNull();
        assertThat(seen.get("traceFlags")).isNull();

        assertThat(response.getHeader("X-Request-ID")).isNotNull();
        assertThat(response.getHeader("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

        assertThat(MDC.get("trace_id")).isNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    public void agentSpanPresentDoesNotDuplicateTraceKeys() throws Exception {
        SpanContext ctx = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> seen;
        try (Scope scope = Span.wrap(ctx).makeCurrent()) {
            seen = mdcSeenByChain(request, response);
        }

        assertThat(seen.get("trace_id")).isNull();
        assertThat(seen.get("span_id")).isNull();
        assertThat(seen.get("traceId")).isNull();
        assertThat(seen.get("spanId")).isNull();

        assertThat(seen.get("requestId")).isNotNull();
        assertThat(response.getHeader("X-Request-ID")).isNotNull();
    }
}
