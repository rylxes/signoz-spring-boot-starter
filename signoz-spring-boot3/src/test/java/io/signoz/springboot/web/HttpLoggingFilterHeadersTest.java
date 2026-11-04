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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code signoz.web.log-headers} on, credential-carrying request headers are masked by the
 * default rules, without the application configuring anything.
 */
@SpringBootTest(
        classes = {TestSigNozApplication.class, HttpLoggingFilterTest.TestController.class},
        properties = "signoz.web.log-headers=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpLoggingFilterHeadersTest {

    /** Planted in the session cookie; it must never appear in the log line. */
    private static final String CANARY = "s3cret";

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
    void cookieHeaderIsMaskedByDefault() throws Exception {
        mockMvc.perform(get("/test")
                        .header("Cookie", "SESSION=" + CANARY)
                        .header("X-Tenant", "acme"))
                .andExpect(status().isOk());

        String line = lastLineFor("/test");
        assertThat(line).contains("headers=").contains("acme").doesNotContain(CANARY);
    }

    private String lastLineFor(String path) {
        String found = null;
        for (ILoggingEvent event : listAppender.list) {
            if (event.getFormattedMessage().contains(path)) {
                found = event.getFormattedMessage();
            }
        }
        assertThat(found).as("log line for " + path).isNotNull();
        return found;
    }
}
