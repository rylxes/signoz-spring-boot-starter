package io.signoz.springboot.logging.log4j2;

import io.signoz.springboot.detect.AgentDetector;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozErrorProperties;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.properties.SigNozSamplingProperties;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the SigNoz logging setup to a Log4j2 {@link LoggerContext}, mirroring what
 * {@code SigNozLoggingAutoConfiguration} does for Logback:
 *
 * <ul>
 *   <li><b>JSON / BOTH mode</b>: unless the root logger already writes JSON, adds a console
 *       appender ({@value #JSON_APPENDER_NAME}) with {@link SigNozLog4j2JsonLayout} and removes
 *       Spring Boot's default {@code Console} appender from the root logger.</li>
 *   <li><b>OTLP / BOTH mode</b>: adds {@link OtlpLog4j2Appender} ({@value #OTLP_APPENDER_NAME}),
 *       unless the OpenTelemetry Java agent is exporting Log4j2 logs itself.</li>
 *   <li><b>Error fingerprinting</b>: {@code errorId} on ERROR events with a throwable, added by
 *       the layout and the OTLP appender.</li>
 *   <li><b>Sampling</b>: a context-wide {@link SamplingLog4j2Filter}.</li>
 * </ul>
 *
 * <p>Idempotent: running it again on the same configuration adds nothing twice.
 */
public final class SigNozLog4j2Configurer {

    public static final String JSON_APPENDER_NAME = "SIGNOZ_JSON";
    public static final String OTLP_APPENDER_NAME = "SIGNOZ_OTLP";
    /** Name of the console appender in Spring Boot's default {@code log4j2(-spring).xml}. */
    static final String SPRING_BOOT_CONSOLE = "Console";

    private static final Logger log = LoggerFactory.getLogger(SigNozLog4j2Configurer.class);

    private SigNozLog4j2Configurer() {
        // utility class
    }

    public static void configure(LoggerContext context, SigNozProperties props, MaskingRegistry maskingRegistry) {
        Configuration config = context.getConfiguration();
        LoggerConfig root = config.getRootLogger();
        SigNozLoggingProperties loggingProps = props.getLogging();
        SigNozLoggingProperties.LoggingMode mode = loggingProps.getMode();

        SigNozErrorProperties errorProps = props.getErrors();
        int errorDepth = errorProps != null && errorProps.isEnabled() ? errorProps.getFingerprintDepth() : 0;

        boolean json = mode == SigNozLoggingProperties.LoggingMode.JSON
                || mode == SigNozLoggingProperties.LoggingMode.BOTH;
        boolean otlp = mode == SigNozLoggingProperties.LoggingMode.OTLP
                || mode == SigNozLoggingProperties.LoggingMode.BOTH;

        if (json && !writesJson(root)) {
            ConsoleAppender console = ConsoleAppender.newBuilder()
                    .setName(JSON_APPENDER_NAME)
                    .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                    .setLayout(new SigNozLog4j2JsonLayout(maskingRegistry, errorDepth))
                    .setConfiguration(config)
                    .build();
            console.start();
            config.addAppender(console);
            if (root.getAppenders().containsKey(SPRING_BOOT_CONSOLE)) {
                root.removeAppender(SPRING_BOOT_CONSOLE);
            }
            root.addAppender(console, null, null);
            log.info("[SigNoz] Log4j2 JSON console appender attached (replaced default Console)");
        }

        if (otlp) {
            if (AgentDetector.isAgentHandlingLog4j2Logs()) {
                log.info("[SigNoz] OpenTelemetry Java Agent detected — skipping Log4j2 OTLP appender "
                        + "(agent handles log export)");
                if (loggingProps.isMaskEnabled() && !loggingProps.getMaskedFields().isEmpty()) {
                    log.warn("[SigNoz] signoz.logging.masked-fields is configured ({} fields) but log export"
                            + " is being handled by the OpenTelemetry Java Agent, which does NOT apply"
                            + " masking. Exported log bodies will be UNMASKED. Either set"
                            + " OTEL_INSTRUMENTATION_LOG4J_APPENDER_ENABLED=false so this starter owns"
                            + " log export, or mask at the call site.",
                            loggingProps.getMaskedFields().size());
                }
            } else if (!root.getAppenders().containsKey(OTLP_APPENDER_NAME)) {
                OtlpLog4j2Appender appender = new OtlpLog4j2Appender(OTLP_APPENDER_NAME);
                OtlpExporterSettings exportSettings = OtlpExporterSettings.resolve(props);
                appender.setEndpoint(exportSettings.endpoint(OtlpExporterSettings.Signal.LOGS));
                appender.setProtocol(exportSettings.getProtocolName());
                appender.setServiceName(props.getServiceName());
                appender.setServiceVersion(props.getServiceVersion());
                appender.setEnvironment(props.getEnvironment());
                appender.setHeaders(exportSettings.getHeaders());
                appender.setMaskingRegistry(maskingRegistry);
                appender.setErrorFingerprintDepth(errorDepth);
                attach(config, root, appender);
                log.info("[SigNoz] Log4j2 OTLP log appender attached → {} ({})",
                        exportSettings.endpoint(OtlpExporterSettings.Signal.LOGS), exportSettings.getProtocolName());
            }
        }

        SigNozSamplingProperties sampling = loggingProps.getSampling();
        if (sampling != null && sampling.isEnabled() && !hasSamplingFilter(config)) {
            SamplingLog4j2Filter filter = new SamplingLog4j2Filter(sampling.getRate(), sampling.getAlwaysLogLevels());
            filter.start();
            config.addFilter(filter);
            log.info("[SigNoz] Log4j2 log sampling enabled (rate: {}, always: {})",
                    sampling.getRate(), sampling.getAlwaysLogLevels());
        }

        context.updateLoggers();
    }

    /** Starts {@code appender} and attaches it to the root logger. */
    static void attach(Configuration config, LoggerConfig root, Appender appender) {
        appender.start();
        config.addAppender(appender);
        root.addAppender(appender, null, null);
    }

    /** True when a root appender already writes JSON (ours, Log4j2's JsonLayout/JsonTemplateLayout, …). */
    static boolean writesJson(LoggerConfig root) {
        for (Appender appender : root.getAppenders().values()) {
            Layout<?> layout = appender.getLayout();
            if (layout instanceof SigNozLog4j2JsonLayout
                    || (layout != null && layout.getClass().getSimpleName().contains("Json"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSamplingFilter(Configuration config) {
        org.apache.logging.log4j.core.Filter filter = config.getFilter();
        if (filter instanceof SamplingLog4j2Filter) {
            return true;
        }
        if (filter instanceof org.apache.logging.log4j.core.filter.CompositeFilter) {
            for (org.apache.logging.log4j.core.Filter f
                    : ((org.apache.logging.log4j.core.filter.CompositeFilter) filter).getFiltersArray()) {
                if (f instanceof SamplingLog4j2Filter) {
                    return true;
                }
            }
        }
        return false;
    }
}
