package io.signoz.springboot.logging.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Log4j2 counterpart of the Logback {@code SamplingTurboFilter}: keeps a random
 * {@code rate} fraction of log events, always keeping the configured levels (ERROR and
 * WARN by default).
 *
 * <p>Installed as a context-wide filter, so like a Logback turbo filter it runs before an
 * event is created and covers every logger and appender. Log4j2 consults context-wide
 * filters through the pre-event {@code filter(Logger, Level, …)} overloads, all of which are
 * routed to one decision here; {@link #filter(LogEvent)} stays neutral so an event can never
 * be sampled twice.
 */
public class SamplingLog4j2Filter extends AbstractFilter {

    private final double rate;
    private final List<String> alwaysLogLevels;

    public SamplingLog4j2Filter(double rate, List<String> alwaysLogLevels) {
        super(Result.NEUTRAL, Result.DENY);
        this.rate = rate;
        this.alwaysLogLevels = alwaysLogLevels == null
                ? new ArrayList<String>() : new ArrayList<String>(alwaysLogLevels);
    }

    /** {@link Result#NEUTRAL} keeps the event; {@link Result#DENY} drops it. */
    Result decide(Level level) {
        if (level != null) {
            for (String always : alwaysLogLevels) {
                if (always.equalsIgnoreCase(level.name())) {
                    return Result.NEUTRAL;
                }
            }
        }
        return ThreadLocalRandom.current().nextDouble() < rate ? Result.NEUTRAL : Result.DENY;
    }

    @Override
    public Result filter(LogEvent event) {
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3, Object p4) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3, Object p4, Object p5) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3, Object p4, Object p5, Object p6) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        return decide(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1,
                         Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8,
                         Object p9) {
        return decide(level);
    }
}
