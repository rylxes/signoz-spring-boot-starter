package io.signoz.springboot.errors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

/**
 * Logback {@link TurboFilter} that assigns a deterministic {@code errorId} to
 * every ERROR-level log event that carries a throwable.
 *
 * <p>The fingerprint is computed by {@link ErrorFingerprint#generate(Throwable, int)}
 * and placed into the MDC under key {@code "errorId"}, making it available for
 * structured log output and trace correlation.</p>
 */
public class ErrorFingerprintTurboFilter extends TurboFilter {

    private int fingerprintDepth = 3;

    /**
     * Evaluate the log event.  If the level is ERROR and a throwable is present,
     * compute a fingerprint and store it in the MDC.  Always returns
     * {@link FilterReply#NEUTRAL} so downstream filters/appenders are unaffected.
     *
     * @param marker    the marker (may be {@code null})
     * @param logger    the logger
     * @param level     the log level
     * @param format    the message format
     * @param params    the message parameters
     * @param throwable the throwable (may be {@code null})
     * @return {@link FilterReply#NEUTRAL}
     */
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable throwable) {
        if (Level.ERROR.equals(level) && throwable != null) {
            String fingerprint = ErrorFingerprint.generate(throwable, fingerprintDepth);
            MDC.put("errorId", fingerprint);
        }
        return FilterReply.NEUTRAL;
    }

    /**
     * Set the number of stack-trace frames used for fingerprint computation.
     *
     * @param fingerprintDepth the depth (must be positive)
     */
    public void setFingerprintDepth(int fingerprintDepth) {
        this.fingerprintDepth = fingerprintDepth;
    }
}
