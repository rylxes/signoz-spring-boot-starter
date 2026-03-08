package io.signoz.springboot.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.signoz.springboot.TestSigNozApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link HttpLoggingFilter}.
 *
 * <p>Uses {@code @SpringBootTest} with {@code @AutoConfigureMockMvc} to exercise
 * the full filter chain. A Logback {@link ListAppender} is attached to the
 * {@code SIGNOZ_HTTP} logger (the logger used by {@link HttpLoggingFilter}) to
 * capture log output for assertion.
 *
 * <p>An inner {@link TestController} provides a {@code /test} endpoint for
 * exercising the logging path, and the built-in {@code /actuator/health} endpoint
 * is used to verify that excluded paths are not logged.
 */
@SpringBootTest(classes = {TestSigNozApplication.class, HttpLoggingFilterTest.TestController.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpLoggingFilterTest {

    @RestController
    static class TestController {
        @GetMapping(value = "/test", produces = MediaType.TEXT_PLAIN_VALUE)
        public String hello() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger httpLogger;

    @BeforeEach
    void setUp() {
        httpLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_HTTP");
        listAppender = new ListAppender<>();
        listAppender.start();
        httpLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        httpLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void getRequestIsLogged() throws Exception {
        mockMvc.perform(get("/test"))
                .andExpect(status().isOk());

        assertThat(listAppender.list).isNotEmpty();
        boolean found = false;
        for (ILoggingEvent event : listAppender.list) {
            if (event.getFormattedMessage().contains("/test")) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("Expected at least one log entry mentioning /test")
                .isTrue();
    }

    @Test
    void statusCodeIncludedInLog() throws Exception {
        mockMvc.perform(get("/test"))
                .andExpect(status().isOk());

        boolean statusFound = false;
        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();
            if (msg.contains("status=200") || msg.contains("status=2")) {
                statusFound = true;
                break;
            }
        }
        assertThat(statusFound)
                .as("Expected log entry to contain the HTTP status code")
                .isTrue();
    }

    @Test
    void excludedPathIsNotLogged() throws Exception {
        listAppender.list.clear();

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        for (ILoggingEvent event : listAppender.list) {
            assertThat(event.getFormattedMessage())
                    .as("Filter should not log excluded path /actuator/health")
                    .doesNotContain("/actuator/health");
        }
    }
}
