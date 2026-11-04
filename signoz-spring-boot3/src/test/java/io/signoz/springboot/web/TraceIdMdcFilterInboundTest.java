package io.signoz.springboot.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Inbound-header paths of {@link TraceIdMdcFilter}: traceparent continuation and X-Request-ID. */
class TraceIdMdcFilterInboundTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT_SPAN = "b7ad6b7169203331";

    @Test
    void continuesAnInboundTraceparentWithAFreshSpan() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("traceparent", "00-" + TRACE_ID + "-" + PARENT_SPAN + "-00");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> seen = new HashMap<>();

        new TraceIdMdcFilter().doFilter(request, response, capture(seen));

        String traceparent = response.getHeader("traceparent");
        assertThat(traceparent).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-00");
        assertThat(traceparent).doesNotContain(PARENT_SPAN);
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(TRACE_ID);
        assertThat(seen).containsEntry("trace_id", TRACE_ID)
                .containsEntry("trace_flags", "00")
                .containsEntry("span_id", traceparent.substring(36, 52))
                .containsEntry("requestId", TRACE_ID);
        assertThat(MDC.get("trace_id")).isNull();
    }

    @Test
    void echoesAnInboundRequestIdAndDerivesAHexTraceIdFromIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("X-Request-ID", "order-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> seen = new HashMap<>();

        new TraceIdMdcFilter().doFilter(request, response, capture(seen));

        assertThat(response.getHeader("X-Request-ID")).isEqualTo("order-42");
        assertThat(response.getHeader("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(seen).containsEntry("requestId", "order-42");
        assertThat(seen.get("trace_id")).matches("[0-9a-f]{32}");

        // The same X-Request-ID always maps to the same trace ID.
        MockHttpServletResponse again = new MockHttpServletResponse();
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/orders");
        second.addHeader("X-Request-ID", "order-42");
        new TraceIdMdcFilter().doFilter(second, again, new MockFilterChain());
        assertThat(again.getHeader("traceparent").substring(3, 35)).isEqualTo(seen.get("trace_id"));
    }

    @Test
    void ignoresAMalformedTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("traceparent", "not-a-traceparent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdMdcFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(response.getHeader("X-Request-ID"))
                .isEqualTo(response.getHeader("traceparent").substring(3, 35));
    }

    private static MockFilterChain capture(final Map<String, String> seen) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                for (String key : new String[]{"trace_id", "span_id", "trace_flags", "requestId"}) {
                    seen.put(key, MDC.get(key));
                }
            }
        };
    }
}
