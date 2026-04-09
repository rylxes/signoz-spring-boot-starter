package io.signoz.springboot.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Logback {@link TurboFilter} that probabilistically samples log events.
 *
 * <p>Events whose level appears in {@link #alwaysLogLevels} (case-insensitive)
 * are always accepted. All other events are accepted with probability
 * {@link #rate} and denied otherwise.</p>
 *
 * <p>This filter is designed to reduce log volume in high-throughput services
 * while preserving important events such as errors and warnings.</p>
 */
public class SamplingTurboFilter extends TurboFilter {

    private double rate = 1.0;

    private List<String> alwaysLogLevels = new ArrayList<>(Arrays.asList("ERROR", "WARN"));

    /**
     * Decide whether to accept or deny the log event.
     *
     * @param marker    the marker (may be {@code null})
     * @param logger    the logger
     * @param level     the log level
     * @param format    the message format
     * @param params    the message parameters
     * @param throwable the throwable (may be {@code null})
     * @return {@link FilterReply#NEUTRAL} if the event should be logged,
     *         {@link FilterReply#DENY} if it should be dropped
     */
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable throwable) {
        if (level != null && isAlwaysLogged(level.toString())) {
            return FilterReply.NEUTRAL;
        }
        if (ThreadLocalRandom.current().nextDouble() < rate) {
            return FilterReply.NEUTRAL;
        }
        return FilterReply.DENY;
    }

    /**
     * Check whether the given level string is in the always-log list (case-insensitive).
     */
    private boolean isAlwaysLogged(String levelName) {
        for (String exempt : alwaysLogLevels) {
            if (exempt.equalsIgnoreCase(levelName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set the sampling rate.
     *
     * @param rate a value between 0.0 (deny all) and 1.0 (accept all)
     */
    public void setRate(double rate) {
        this.rate = rate;
    }

    /**
     * Set the list of log levels that bypass sampling and are always accepted.
     *
     * @param alwaysLogLevels level names (case-insensitive), e.g. {@code ["ERROR", "WARN"]}
     */
    public void setAlwaysLogLevels(List<String> alwaysLogLevels) {
        this.alwaysLogLevels = alwaysLogLevels;
    }
}
