package io.signoz.springboot.diagnostics;

import io.signoz.springboot.detect.AgentDetector;
import io.signoz.springboot.properties.SigNozProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Logs a summary of the active SigNoz configuration at startup.
 *
 * <p>Implements {@link SmartInitializingSingleton} so the summary is printed
 * after all singleton beans have been fully initialized, giving an accurate
 * picture of which features are active.
 *
 * <p>Example output:
 * <pre>
 * [SigNoz] Config Summary:
 *   endpoint=http://localhost:4317, service=my-app, env=production
 *   agent=DETECTED
 *   masking=ON (14 fields), tracing=ON (sample=1.0)
 *   web=ON, audit=ON
 *   timed=OFF, outbound=OFF, messaging=OFF
 *   database=OFF (slow&gt;500ms)
 *   errors=OFF, sampling=OFF
 *   userContext=OFF, async=OFF, alerts=OFF
 * </pre>
 */
public class SigNozStartupDiagnostics implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SigNozStartupDiagnostics.class);

    private final SigNozProperties props;

    /**
     * Creates a new diagnostics bean.
     *
     * @param props the root SigNoz configuration properties
     */
    public SigNozStartupDiagnostics(SigNozProperties props) {
        this.props = props;
    }

    @Override
    public void afterSingletonsInstantiated() {
        StringBuilder sb = new StringBuilder();
        sb.append("[SigNoz] Config Summary:\n");

        // Line 1: endpoint, service, environment
        sb.append("  endpoint=").append(props.getEndpoint())
                .append(", service=").append(props.getServiceName())
                .append(", env=").append(props.getEnvironment())
                .append("\n");

        // Line 2: agent detection
        String agentStatus = AgentDetector.isAgentPresent() ? "DETECTED" : "NOT_DETECTED";
        sb.append("  agent=").append(agentStatus).append("\n");

        // Line 3: masking and tracing
        String maskingStatus = props.getLogging().isMaskEnabled() ? "ON" : "OFF";
        int maskedFieldCount = props.getLogging().getMaskedFields() != null
                ? props.getLogging().getMaskedFields().size() : 0;
        String tracingStatus = props.getTracing().isEnabled() ? "ON" : "OFF";
        double sampleRate = props.getTracing().getSampleRate();
        sb.append("  masking=").append(maskingStatus)
                .append(" (").append(maskedFieldCount).append(" fields)")
                .append(", tracing=").append(tracingStatus)
                .append(" (sample=").append(sampleRate).append(")")
                .append("\n");

        // Line 4: web and audit
        String webStatus = props.getWeb().isLogRequests() ? "ON" : "OFF";
        String auditStatus = props.getAudit().isEnabled() ? "ON" : "OFF";
        sb.append("  web=").append(webStatus)
                .append(", audit=").append(auditStatus)
                .append("\n");

        // Line 5: timed, outbound, messaging (may not be wired yet)
        String timedStatus = safeGetEnabled("getTimed") ? "ON" : "OFF";
        String outboundStatus = safeGetEnabled("getOutbound") ? "ON" : "OFF";
        String messagingStatus = safeGetEnabled("getMessaging") ? "ON" : "OFF";
        sb.append("  timed=").append(timedStatus)
                .append(", outbound=").append(outboundStatus)
                .append(", messaging=").append(messagingStatus)
                .append("\n");

        // Line 6: database (with slow threshold)
        String databaseStatus = "OFF";
        long slowThreshold = 500L;
        try {
            Object dbProps = props.getClass().getMethod("getDatabase").invoke(props);
            if (dbProps != null) {
                Boolean enabled = (Boolean) dbProps.getClass().getMethod("isEnabled").invoke(dbProps);
                if (Boolean.TRUE.equals(enabled)) {
                    databaseStatus = "ON";
                }
                Object threshold = dbProps.getClass().getMethod("getSlowQueryThresholdMs").invoke(dbProps);
                if (threshold instanceof Number) {
                    slowThreshold = ((Number) threshold).longValue();
                }
            }
        } catch (Exception ignored) {
            // database properties not available yet
        }
        sb.append("  database=").append(databaseStatus)
                .append(" (slow>").append(slowThreshold).append("ms)")
                .append("\n");

        // Line 7: errors, sampling
        String errorsStatus = safeGetEnabled("getErrors") ? "ON" : "OFF";
        String samplingStatus = safeGetEnabled("getSampling") ? "ON" : "OFF";
        sb.append("  errors=").append(errorsStatus)
                .append(", sampling=").append(samplingStatus)
                .append("\n");

        // Line 8: userContext, async, alerts
        String userContextStatus = safeGetEnabled("getUserContext") ? "ON" : "OFF";
        String asyncStatus = safeGetEnabled("getAsync") ? "ON" : "OFF";
        String alertsStatus = safeGetEnabled("getAlerts") ? "ON" : "OFF";
        sb.append("  userContext=").append(userContextStatus)
                .append(", async=").append(asyncStatus)
                .append(", alerts=").append(alertsStatus);

        log.info(sb.toString());
    }

    /**
     * Safely checks if a nested property group is enabled via reflection.
     * Returns {@code false} if the getter does not exist or returns {@code null}.
     *
     * @param getterName the getter method name on {@link SigNozProperties}
     * @return {@code true} if the nested property object exists and its {@code isEnabled()} returns true
     */
    private boolean safeGetEnabled(String getterName) {
        try {
            Object nested = props.getClass().getMethod(getterName).invoke(props);
            if (nested != null) {
                Object enabled = nested.getClass().getMethod("isEnabled").invoke(nested);
                return Boolean.TRUE.equals(enabled);
            }
        } catch (Exception ignored) {
            // property group not wired yet
        }
        return false;
    }
}
