package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.detect.AgentDetector;
import io.signoz.springboot.logging.OtlpLogbackAppender;
import io.signoz.springboot.logging.SigNozJsonEncoder;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozProperties;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import net.logstash.logback.composite.loggingevent.MdcJsonProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Auto-configures the SigNoz logging pipeline:
 * <ul>
 *   <li>Registers {@link MaskingRegistry} with configured masked fields</li>
 *   <li>Programmatically attaches {@link OtlpLogbackAppender} and/or
 *       {@link SigNozJsonEncoder} to the root Logback logger based on
 *       {@code signoz.logging.mode}</li>
 * </ul>
 */
@Configuration
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
public class SigNozLoggingAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(SigNozLoggingAutoConfiguration.class);

    private final SigNozProperties props;

    public SigNozLoggingAutoConfiguration(SigNozProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean
    public MaskingRegistry maskingRegistry() {
        return new MaskingRegistry(props.getLogging());
    }

    @Configuration
    @ConditionalOnClass(name = "net.logstash.logback.encoder.LogstashEncoder")
    static class JsonEncoderConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SigNozJsonEncoder sigNozJsonEncoder(MaskingRegistry maskingRegistry) {
            SigNozJsonEncoder encoder = new SigNozJsonEncoder();
            encoder.setMaskingRegistry(maskingRegistry);
            return encoder;
        }
    }

    /**
     * SmartInitializingSingleton runs after all beans are created, ensuring
     * the Logback context is fully initialised before we modify it.
     */
    @Bean
    public SmartInitializingSingleton sigNozLogbackConfigurer(MaskingRegistry maskingRegistry) {
        return () -> configureLogback(maskingRegistry);
    }

    /** MDC keys to auto-inject into JSON log output for trace correlation. */
    private static final List<String> TRACE_MDC_KEYS = Arrays.asList(
            "traceId", "spanId", "traceFlags", "requestId",
            "trace_id", "span_id"  // OTEL agent uses underscored keys
    );

    private void configureLogback(MaskingRegistry maskingRegistry) {
        SigNozLoggingProperties loggingProps = props.getLogging();
        SigNozLoggingProperties.LoggingMode mode = loggingProps.getMode();

        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger rootLogger =
                    context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

            // --- Auto-inject MDC trace fields into existing JSON appenders ---
            injectMdcProviders(rootLogger, context);

            boolean otlp = mode == SigNozLoggingProperties.LoggingMode.OTLP
                    || mode == SigNozLoggingProperties.LoggingMode.BOTH;

            if (otlp) {
                if (AgentDetector.isAgentPresent()) {
                    log.info("[SigNoz] OpenTelemetry Java Agent detected — skipping OTLP log appender (agent handles log export)");
                } else if (rootLogger.getAppender("SIGNOZ_OTLP") == null) {
                    OtlpLogbackAppender otlpAppender = new OtlpLogbackAppender();
                    otlpAppender.setName("SIGNOZ_OTLP");
                    otlpAppender.setContext(context);
                    otlpAppender.setEndpoint(props.getEndpoint());
                    otlpAppender.setServiceName(props.getServiceName());
                    otlpAppender.setServiceVersion(props.getServiceVersion());
                    otlpAppender.setEnvironment(props.getEnvironment());
                    otlpAppender.setHeaders(props.getHeaders());
                    otlpAppender.start();
                    rootLogger.addAppender(otlpAppender);
                    log.info("[SigNoz] OTLP log appender attached → {}", props.getEndpoint());
                }
            }

        } catch (Exception e) {
            log.warn("[SigNoz] Could not configure Logback programmatically: {}", e.getMessage());
        }
    }

    /**
     * Walks all appenders on the root logger and injects an {@link MdcJsonProvider}
     * for trace correlation keys into any {@link LoggingEventCompositeJsonEncoder}.
     * This ensures traceId/requestId appear in JSON logs without the user modifying
     * their logback.xml.
     */
    private void injectMdcProviders(ch.qos.logback.classic.Logger rootLogger,
                                    LoggerContext context) {
        try {
            Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it =
                    rootLogger.iteratorForAppenders();
            while (it.hasNext()) {
                Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender = it.next();
                if (appender instanceof ch.qos.logback.core.OutputStreamAppender) {
                    ch.qos.logback.core.OutputStreamAppender<?> osa =
                            (ch.qos.logback.core.OutputStreamAppender<?>) appender;
                    ch.qos.logback.core.encoder.Encoder<?> encoder = osa.getEncoder();

                    if (encoder instanceof LoggingEventCompositeJsonEncoder) {
                        LoggingEventCompositeJsonEncoder composite =
                                (LoggingEventCompositeJsonEncoder) encoder;
                        // Check if an MdcJsonProvider is already present
                        boolean hasMdc = composite.getProviders().getProviders().stream()
                                .anyMatch(p -> p instanceof MdcJsonProvider);
                        if (!hasMdc) {
                            MdcJsonProvider mdcProvider = new MdcJsonProvider();
                            for (String key : TRACE_MDC_KEYS) {
                                mdcProvider.addIncludeMdcKeyName(key);
                            }
                            mdcProvider.setContext(context);
                            mdcProvider.start();
                            composite.getProviders().addProvider(mdcProvider);
                            log.info("[SigNoz] Injected MDC trace fields into appender '{}'",
                                    appender.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SigNoz] Could not inject MDC providers: {}", e.getMessage());
        }
    }
}
