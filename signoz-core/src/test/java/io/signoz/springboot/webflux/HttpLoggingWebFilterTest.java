package io.signoz.springboot.webflux;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozWebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLoggingWebFilterTest {

    /** Value planted in the request body's password; it must never appear in the log line. */
    private static final String CANARY = "s3cret";

    /** Value planted in the response body's token: masked in the log, delivered intact to the client. */
    private static final String TOKEN_CANARY = "t0ken-canary";

    private SigNozWebProperties webProps;
    private HttpLoggingWebFilter filter;
    private ListAppender<ILoggingEvent> appender;
    private Logger httpLogger;

    @BeforeEach
    void setUp() {
        webProps = new SigNozWebProperties();
        webProps.setLogResponseBody(true);
        filter = new HttpLoggingWebFilter(webProps, new MaskingRegistry(new SigNozLoggingProperties()));

        httpLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_HTTP");
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        httpLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        httpLogger.detachAppender(appender);
    }

    @Test
    void logsStatusDurationAndMaskedBodiesWithTheRequestMdc() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/login")
                .queryParam("tenant", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"user\":\"alice\",\"password\":\"" + CANARY + "\"}"));

        WebFilterChain handler = ex -> DataBufferUtils.join(ex.getRequest().getBody())
                .flatMap(body -> {
                    DataBufferUtils.release(body);
                    ex.getResponse().setStatusCode(HttpStatus.CREATED);
                    return write(ex, "{\"token\":\"" + TOKEN_CANARY + "\",\"ok\":true}");
                });

        // TraceIdWebFilter runs first in a real app; it supplies the MDC for the log line.
        new TraceIdWebFilter().filter(exchange, ex -> filter.filter(ex, handler)).block();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        String line = event.getFormattedMessage();
        assertThat(line).startsWith("HTTP POST /login?tenant=acme status=201 duration=");
        assertThat(line).contains("requestBody=").contains("\"user\":\"alice\"").doesNotContain(CANARY);
        assertThat(line).contains("responseBody=").contains("\"ok\":true").doesNotContain(TOKEN_CANARY);
        assertThat(event.getMDCPropertyMap()).containsKeys("requestId", "trace_id", "span_id");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains(TOKEN_CANARY);
    }

    @Test
    void masksSensitiveQueryParametersAndKeepsOthers() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/search?tenant=acme&token=q-canary&api_key=k-canary&password=p-canary"));

        filter.filter(exchange, ex -> ex.getResponse().setComplete()).block();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("tenant=acme")
                .doesNotContain("q-canary", "k-canary", "p-canary");
    }

    @Test
    void boundsCapturedBodiesToMaxBodyBytes() {
        webProps.setMaxBodyBytes(4);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/big"));

        filter.filter(exchange, ex -> write(ex, "abcdefghij")).block();

        assertThat(appender.list.get(0).getFormattedMessage()).endsWith("responseBody=abcd");
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("abcdefghij");
    }

    @Test
    void logsTheStatusTheErrorHandlerCommits() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));

        filter.filter(exchange, ex -> Mono.error(new IllegalStateException("boom")))
                // What WebFlux's exception handling does after the filter chain fails.
                .onErrorResume(ex -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .block();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .startsWith("HTTP GET /missing status=404 ")
                .contains("error=java.lang.IllegalStateException");
    }

    @Test
    void skipsExcludedPaths() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));

        filter.filter(exchange, ex -> ex.getResponse().setComplete()).block();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void skipsEverythingWhenRequestLoggingIsDisabled() {
        webProps.setLogRequests(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));

        filter.filter(exchange, ex -> ex.getResponse().setComplete()).block();

        assertThat(appender.list).isEmpty();
    }

    private static Mono<Void> write(ServerWebExchange exchange, String body) {
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
