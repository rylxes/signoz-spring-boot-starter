package io.signoz.springboot.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the MDC keys {@link TraceIdMdcFilter} writes.
 *
 * <p>Contract:
 * <ul>
 *   <li>Trace correlation is emitted with OpenTelemetry-native snake_case keys
 *       ({@code trace_id}/{@code span_id}/{@code trace_flags}) so it matches what
 *       the OTel agent emits and is queryable in SigNoz — never the legacy
 *       camelCase duplicates.</li>
 *   <li>When an OTel agent already populated a valid span, the filter does NOT
 *       write the trace trio at all (the agent owns those keys); it only adds
 *       {@code requestId}.</li>
 *   <li>{@code requestId} is always present; all keys are cleared afterwards.</li>
 * </ul>
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

        assertThat(seen.get("trace_id")).as("snake_case trace_id").isNotNull().hasSize(32);
        assertThat(seen.get("span_id")).as("snake_case span_id").isNotNull().hasSize(16);
        assertThat(seen.get("trace_flags")).as("snake_case trace_flags").isNotNull();
        assertThat(seen.get("requestId")).as("requestId always present").isNotNull();

        assertThat(seen.get("traceId")).as("no legacy camelCase traceId").isNull();
        assertThat(seen.get("spanId")).as("no legacy camelCase spanId").isNull();
        assertThat(seen.get("traceFlags")).as("no legacy camelCase traceFlags").isNull();

        // response propagation headers still set
        assertThat(response.getHeader("X-Request-ID")).isNotNull();
        assertThat(response.getHeader("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

        // MDC cleared after the request
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

        // Agent owns trace_id/span_id/trace_flags — the filter must NOT add its own copies.
        assertThat(seen.get("trace_id")).as("filter defers trace_id to the agent").isNull();
        assertThat(seen.get("span_id")).isNull();
        assertThat(seen.get("traceId")).as("no legacy camelCase either").isNull();
        assertThat(seen.get("spanId")).isNull();

        // still contributes requestId
        assertThat(seen.get("requestId")).isNotNull();
        assertThat(response.getHeader("X-Request-ID")).isNotNull();
    }
}
