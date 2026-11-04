package io.signoz.springboot.webflux;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.signoz.springboot.TestSigNozApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end WebFlux behaviour on Spring Boot 3: real WebFlux dispatching and Spring
 * Boot's error handling, with the SigNoz reactive filters auto-configured.
 */
@SpringBootTest(
        classes = {TestSigNozApplication.class, ReactiveWebIntegrationTest.RxController.class},
        properties = "spring.main.web-application-type=reactive")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ReactiveWebIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final org.slf4j.Logger handlerLog = LoggerFactory.getLogger("RX_HANDLER");

    @RestController
    static class RxController {

        @GetMapping("/rx/hello")
        public Mono<String> hello() {
            handlerLog.info("before hop");
            return Mono.just("hi")
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(v -> handlerLog.info("after hop"));
        }

        @GetMapping("/rx/missing")
        public Mono<String> missing() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "nope"));
        }

        @GetMapping("/rx/boom")
        public Mono<String> boom() {
            return Mono.error(new IllegalStateException("boom"));
        }
    }

    @Autowired
    private WebTestClient client;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("SIGNOZ_HTTP")).addAppender(appender);
        ((Logger) LoggerFactory.getLogger("RX_HANDLER")).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger("SIGNOZ_HTTP")).detachAppender(appender);
        ((Logger) LoggerFactory.getLogger("RX_HANDLER")).detachAppender(appender);
    }

    @Test
    void echoesTraceHeadersAndLogsTheRequestWithItsIds() {
        EntityExchangeResult<String> result = client.get().uri("/rx/hello")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .header("X-Request-ID", "req-rx-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("hi")
                .returnResult();

        assertThat(result.getResponseHeaders().getFirst("traceparent")).startsWith("00-" + TRACE_ID + "-");
        assertThat(result.getResponseHeaders().getFirst("X-Request-ID")).isEqualTo("req-rx-1");

        ILoggingEvent access = only("SIGNOZ_HTTP");
        assertThat(access.getFormattedMessage()).startsWith("HTTP GET /rx/hello status=200 duration=");
        assertThat(access.getMDCPropertyMap())
                .containsEntry("requestId", "req-rx-1")
                .containsEntry("trace_id", TRACE_ID);
    }

    @Test
    void handlerLogsCarryTheMdcBeforeAndAfterAThreadHop() {
        client.get().uri("/rx/hello")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isOk();

        List<ILoggingEvent> handler = awaitEvents("RX_HANDLER", 2);
        assertThat(handler).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("before hop", "after hop");
        assertThat(handler.get(1).getThreadName()).contains("boundedElastic");
        for (ILoggingEvent event : handler) {
            assertThat(event.getMDCPropertyMap())
                    .as("MDC of '%s'", event.getFormattedMessage())
                    .containsEntry("trace_id", TRACE_ID)
                    .containsKey("requestId");
        }
    }

    @Test
    void logsTheStatusChosenByTheErrorHandler() {
        client.get().uri("/rx/missing").exchange().expectStatus().isNotFound();
        assertThat(only("SIGNOZ_HTTP").getFormattedMessage())
                .startsWith("HTTP GET /rx/missing status=404 ")
                .contains("error=org.springframework.web.server.ResponseStatusException");

        synchronized (appender) {
            appender.list.clear();
        }
        client.get().uri("/rx/boom").exchange().expectStatus().is5xxServerError();
        assertThat(only("SIGNOZ_HTTP").getFormattedMessage())
                .startsWith("HTTP GET /rx/boom status=500 ")
                .contains("error=java.lang.IllegalStateException");
    }

    @Test
    void excludedPathsAreNotLogged() {
        client.get().uri("/actuator/whatever").exchange();
        assertThat(events("SIGNOZ_HTTP")).isEmpty();
    }

    /**
     * Snapshot of the captured events for {@code logger}. Logback appends from Reactor threads
     * (the access log is written after the response completes), and {@code AppenderBase.doAppend}
     * holds the appender's lock while it does, so copy the list under the same lock.
     */
    private List<ILoggingEvent> events(String logger) {
        List<ILoggingEvent> snapshot;
        synchronized (appender) {
            snapshot = new ArrayList<>(appender.list);
        }
        return snapshot.stream()
                .filter(e -> e.getLoggerName().equals(logger))
                .collect(Collectors.toList());
    }

    /** Waits (up to 5s) until at least {@code count} events for {@code logger} have arrived. */
    private List<ILoggingEvent> awaitEvents(String logger, int count) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        List<ILoggingEvent> events = events(logger);
        while (events.size() < count && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            events = events(logger);
        }
        return events;
    }

    private ILoggingEvent only(String logger) {
        List<ILoggingEvent> events = awaitEvents(logger, 1);
        assertThat(events).hasSize(1);
        return events.get(0);
    }
}
