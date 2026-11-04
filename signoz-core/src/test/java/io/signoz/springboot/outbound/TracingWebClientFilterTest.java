package io.signoz.springboot.outbound;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import io.signoz.springboot.webflux.ReactiveMdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingWebClientFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    /** Sensitive query parameters and a URL password; none of these values may reach the log. */
    private static final String SENSITIVE_URL = "https://user:u-canary@api.example.com:8443/x"
            + "?tenant=acme&token=q-canary&access%5Ftoken=a-canary,tail-canary&api_key=k-canary";

    private final AtomicReference<ClientRequest> sent = new AtomicReference<ClientRequest>();
    private final ExchangeFunction next = request -> {
        sent.set(request);
        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    };
    private final ClientRequest request =
            ClientRequest.create(HttpMethod.GET, URI.create("http://downstream/api")).build();
    private final ClientRequest sensitiveRequest =
            ClientRequest.create(HttpMethod.GET, URI.create(SENSITIVE_URL)).build();

    private ListAppender<ILoggingEvent> appender;
    private Logger outboundLogger;

    @BeforeEach
    void attachAppender() {
        outboundLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_OUTBOUND");
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        outboundLogger.addAppender(appender);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
        outboundLogger.detachAppender(appender);
    }

    @Test
    void masksSensitiveQueryParametersAndKeepsOthers() {
        new TracingWebClientFilter(new SigNozOutboundProperties(),
                new MaskingRegistry(new SigNozLoggingProperties()))
                .filter(sensitiveRequest, next).block();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("Outbound GET https://api.example.com:8443/x?tenant=acme&token=***&")
                .contains("access%5Ftoken=***")
                .doesNotContain("q-canary", "a-canary", "tail-canary", "k-canary", "u-canary");
        // The request itself still goes out with the original URL.
        assertThat(sent.get().url()).isEqualTo(URI.create(SENSITIVE_URL));
    }

    @Test
    void masksSensitiveQueryParametersWhenTheRequestFails() {
        ExchangeFunction failing = r -> Mono.error(new IOException("refused"));

        assertThatThrownBy(() -> new TracingWebClientFilter(new SigNozOutboundProperties(),
                new MaskingRegistry(new SigNozLoggingProperties()))
                .filter(sensitiveRequest, failing).block())
                .hasCauseInstanceOf(IOException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("tenant=acme&token=***&")
                .contains("FAILED")
                .doesNotContain("q-canary", "a-canary", "tail-canary", "k-canary", "u-canary");
    }

    @Test
    void usesTheInjectedMaskingRules() {
        SigNozLoggingProperties logging = new SigNozLoggingProperties();
        logging.setMaskedFields(Collections.singletonList("tenant"));
        ClientRequest tenantRequest = ClientRequest.create(HttpMethod.GET,
                URI.create("https://api.example.com/x?tenant=acme&page=2")).build();

        new TracingWebClientFilter(new SigNozOutboundProperties(), new MaskingRegistry(logging))
                .filter(tenantRequest, next).block();

        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("https://api.example.com/x?tenant=***&page=2")
                .doesNotContain("acme");
    }

    @Test
    void masksWithTheDefaultRulesWhenNoRegistryIsGiven() {
        new TracingWebClientFilter(new SigNozOutboundProperties()).filter(sensitiveRequest, next).block();

        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("tenant=acme&token=***&")
                .doesNotContain("q-canary", "a-canary", "tail-canary", "k-canary", "u-canary");
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
