package io.signoz.springboot.errors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ErrorFingerprintTurboFilter}.
 */
class ErrorFingerprintTurboFilterTest {

    private ErrorFingerprintTurboFilter filter;
    private Logger logger;

    @BeforeEach
    void setUp() {
        filter = new ErrorFingerprintTurboFilter();
        filter.setFingerprintDepth(3);
        logger = (Logger) LoggerFactory.getLogger(ErrorFingerprintTurboFilterTest.class);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void errorWithThrowableSetsErrorIdInMdc() {
        Throwable throwable = new RuntimeException("test error");
        FilterReply reply = filter.decide(null, logger, Level.ERROR, "msg", null, throwable);

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
        assertThat(MDC.get("errorId")).isNotNull();
        assertThat(MDC.get("errorId")).hasSize(12);
    }

    @Test
    void infoLevelDoesNotSetErrorId() {
        Throwable throwable = new RuntimeException("test error");
        filter.decide(null, logger, Level.INFO, "msg", null, throwable);

        assertThat(MDC.get("errorId")).isNull();
    }

    @Test
    void errorWithoutThrowableDoesNotSetErrorId() {
        filter.decide(null, logger, Level.ERROR, "msg", null, null);

        assertThat(MDC.get("errorId")).isNull();
    }

    @Test
    void alwaysReturnsNeutral() {
        FilterReply replyError = filter.decide(null, logger, Level.ERROR, "msg", null, new RuntimeException());
        FilterReply replyInfo = filter.decide(null, logger, Level.INFO, "msg", null, null);
        FilterReply replyWarn = filter.decide(null, logger, Level.WARN, "msg", null, null);

        assertThat(replyError).isEqualTo(FilterReply.NEUTRAL);
        assertThat(replyInfo).isEqualTo(FilterReply.NEUTRAL);
        assertThat(replyWarn).isEqualTo(FilterReply.NEUTRAL);
    }
}
