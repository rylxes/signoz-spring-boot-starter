package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SamplingTurboFilter}.
 */
class SamplingTurboFilterTest {

    private SamplingTurboFilter filter;
    private Logger logger;

    @BeforeEach
    void setUp() {
        filter = new SamplingTurboFilter();
        filter.setAlwaysLogLevels(new ArrayList<>(Arrays.asList("ERROR", "WARN")));
        logger = (Logger) LoggerFactory.getLogger(SamplingTurboFilterTest.class);
    }

    @Test
    void zeroRateDeniesInfoLevel() {
        filter.setRate(0.0);
        FilterReply reply = filter.decide(null, logger, Level.INFO, "msg", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void fullRateAcceptsInfoLevel() {
        filter.setRate(1.0);
        FilterReply reply = filter.decide(null, logger, Level.INFO, "msg", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void errorAlwaysPassesRegardlessOfRate() {
        filter.setRate(0.0);
        FilterReply reply = filter.decide(null, logger, Level.ERROR, "msg", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void warnAlwaysPassesRegardlessOfRate() {
        filter.setRate(0.0);
        FilterReply reply = filter.decide(null, logger, Level.WARN, "msg", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void debugDeniedAtZeroRate() {
        filter.setRate(0.0);
        FilterReply reply = filter.decide(null, logger, Level.DEBUG, "msg", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void alwaysLogLevelsIsCaseInsensitive() {
        filter.setRate(0.0);
        filter.setAlwaysLogLevels(new ArrayList<>(Arrays.asList("error", "warn")));
        FilterReply reply = filter.decide(null, logger, Level.ERROR, "msg", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }
}
