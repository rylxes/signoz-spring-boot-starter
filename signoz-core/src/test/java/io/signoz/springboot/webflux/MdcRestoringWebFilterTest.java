package io.signoz.springboot.webflux;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.handler.DefaultWebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcRestoringWebFilterTest {

    /** Continues the chain on another thread, as Spring Security's session loading does. */
    private static final WebFilter THREAD_HOP = (exchange, chain) ->
            Mono.just("hop").publishOn(Schedulers.boundedElastic()).then(Mono.defer(() -> chain.filter(exchange)));

    private final AtomicReference<String> requestIdInHandler = new AtomicReference<String>();
    private final AtomicReference<String> handlerThread = new AtomicReference<String>();

    private final WebHandler handler = exchange -> {
        requestIdInHandler.set(MDC.get("requestId"));
        handlerThread.set(Thread.currentThread().getName());
        return Mono.empty();
    };

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void theHandlerSeesTheRequestIdsAfterAFilterHopsThreads() {
        run(new TraceIdWebFilter(), THREAD_HOP, new MdcRestoringWebFilter());

        assertThat(handlerThread.get()).startsWith("boundedElastic");
        assertThat(requestIdInHandler.get()).isEqualTo("r-1");
    }

    @Test
    void withoutItTheHopLosesTheIds() {
        run(new TraceIdWebFilter(), THREAD_HOP);

        assertThat(handlerThread.get()).startsWith("boundedElastic");
        assertThat(requestIdInHandler.get()).isNull();
    }

    @Test
    void passesThroughWhenNoIdsAreInTheContext() {
        run(THREAD_HOP, new MdcRestoringWebFilter());

        assertThat(handlerThread.get()).isNotNull();
        assertThat(requestIdInHandler.get()).isNull();
    }

    private void run(WebFilter... filters) {
        List<WebFilter> list = new ArrayList<WebFilter>(Arrays.asList(filters));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header("X-Request-ID", "r-1"));
        new DefaultWebFilterChain(handler, list).filter(exchange).block();
    }
}
