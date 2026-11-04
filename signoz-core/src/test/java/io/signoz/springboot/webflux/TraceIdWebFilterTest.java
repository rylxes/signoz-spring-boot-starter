package io.signoz.springboot.webflux;

import io.signoz.springboot.tracing.TraceIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdWebFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final TraceIdWebFilter filter = new TraceIdWebFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mintsIdsAndEchoesThemOnTheResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        String traceparent = headers.getFirst("traceparent");
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(headers.getFirst("X-Request-ID")).isEqualTo(traceparent.substring(3, 35));

        TraceIdentity identity = exchange.getAttribute(TraceIdWebFilter.IDENTITY_ATTRIBUTE);
        assertThat(identity).isNotNull();
        assertThat(identity.toTraceparent()).isEqualTo(traceparent);
    }

    @Test
    void continuesAnInboundTraceparentWithANewSpan() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .header("X-Request-ID", "req-42"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        String traceparent = exchange.getResponse().getHeaders().getFirst("traceparent");
        assertThat(traceparent).startsWith("00-" + TRACE_ID + "-").endsWith("-01");
        assertThat(traceparent).doesNotContain("00f067aa0ba902b7");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-ID")).isEqualTo("req-42");
    }

    @Test
    void handlerSeesMdcOnTheThreadAndInTheReactorContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"));
        final AtomicReference<String> threadMdc = new AtomicReference<String>();
        final AtomicReference<Map<String, String>> contextMdc = new AtomicReference<Map<String, String>>();

        WebFilterChain handler = ex -> {
            threadMdc.set(MDC.get("trace_id"));
            // After a thread hop the thread MDC is gone, but the Reactor context keeps it.
            return Mono.deferContextual(ctx -> {
                        contextMdc.set(ReactiveMdc.fromContext(ctx));
                        return Mono.<Void>empty();
                    })
                    .subscribeOn(Schedulers.parallel());
        };

        filter.filter(exchange, handler).block();

        assertThat(threadMdc.get()).isEqualTo(TRACE_ID);
        assertThat(contextMdc.get())
                .containsEntry("trace_id", TRACE_ID)
                .containsEntry("trace_flags", "01")
                .containsKeys("span_id", "requestId");
    }

    @Test
    void restoresThePreviousMdcAfterSubscription() {
        MDC.put("trace_id", "previous");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(MDC.get("trace_id")).isEqualTo("previous");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void reactiveMdcMergesEntriesAndRunsWithThem() {
        Map<String, String> first = new HashMap<String, String>();
        first.put("requestId", "r1");
        Map<String, String> second = new HashMap<String, String>();
        second.put("userId", "alice");

        Map<String, String> merged = ReactiveMdc.fromContext(
                ReactiveMdc.withEntries(ReactiveMdc.withEntries(reactor.util.context.Context.empty(), first), second));
        assertThat(merged).containsEntry("requestId", "r1").containsEntry("userId", "alice");

        final AtomicReference<String> seen = new AtomicReference<String>();
        ReactiveMdc.runWithMdc(merged, () -> seen.set(MDC.get("userId")));
        assertThat(seen.get()).isEqualTo("alice");
        assertThat(MDC.get("userId")).isNull();
    }
}
