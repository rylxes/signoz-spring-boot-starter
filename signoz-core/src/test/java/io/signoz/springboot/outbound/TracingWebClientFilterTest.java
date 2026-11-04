package io.signoz.springboot.outbound;

import io.signoz.springboot.properties.SigNozOutboundProperties;
import io.signoz.springboot.webflux.ReactiveMdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TracingWebClientFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    private final AtomicReference<ClientRequest> sent = new AtomicReference<ClientRequest>();
    private final ExchangeFunction next = request -> {
        sent.set(request);
        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    };
    private final ClientRequest request =
            ClientRequest.create(HttpMethod.GET, URI.create("http://downstream/api")).build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesTheRequestIdentityFromTheReactorContextAfterAThreadHop() {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("requestId", "req-7");
        entries.put("trace_id", TRACE_ID);
        entries.put("span_id", SPAN_ID);
        entries.put("trace_flags", "01");

        new TracingWebClientFilter(new SigNozOutboundProperties())
                .filter(request, next)
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(ctx -> ReactiveMdc.withEntries(ctx, entries))
                .block();

        assertThat(sent.get().headers().getFirst("traceparent"))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(sent.get().headers().getFirst("X-Request-ID")).isEqualTo("req-7");
    }

    @Test
    void fallsBackToTheThreadMdc() {
        MDC.put("trace_id", TRACE_ID);
        MDC.put("span_id", SPAN_ID);
        MDC.put("requestId", "req-8");

        new TracingWebClientFilter(new SigNozOutboundProperties()).filter(request, next).block();

        assertThat(sent.get().headers().getFirst("traceparent"))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(sent.get().headers().getFirst("X-Request-ID")).isEqualTo("req-8");
    }

    @Test
    void sendsNoTraceHeadersWithoutAnIdentity() {
        new TracingWebClientFilter(new SigNozOutboundProperties()).filter(request, next).block();

        assertThat(sent.get().headers().containsKey("traceparent")).isFalse();
        assertThat(sent.get().headers().containsKey("X-Request-ID")).isFalse();
    }
}
