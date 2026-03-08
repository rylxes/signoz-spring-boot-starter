package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.logging.OtlpLogbackAppender;
import io.signoz.springboot.logging.SigNozJsonEncoder;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ch.qos.logback.classic.LoggerContext;

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

    private void configureLogback(MaskingRegistry maskingRegistry) {
        SigNozLoggingProperties loggingProps = props.getLogging();
        SigNozLoggingProperties.LoggingMode mode = loggingProps.getMode();

        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger rootLogger =
                    context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

            boolean otlp = mode == SigNozLoggingProperties.LoggingMode.OTLP
                    || mode == SigNozLoggingProperties.LoggingMode.BOTH;

            if (otlp) {
                // Only add if not already attached (idempotent)
                if (rootLogger.getAppender("SIGNOZ_OTLP") == null) {
                    OtlpLogbackAppender otlpAppender = new OtlpLogbackAppender();
                    otlpAppender.setName("SIGNOZ_OTLP");
                    otlpAppender.setContext(context);
                    otlpAppender.setEndpoint(props.getEndpoint());
                    otlpAppender.setServiceName(props.getServiceName());
                    otlpAppender.setServiceVersion(props.getServiceVersion());
                    otlpAppender.setEnvironment(props.getEnvironment());
                    otlpAppender.start();
                    rootLogger.addAppender(otlpAppender);
                    log.info("[SigNoz] OTLP log appender attached → {}", props.getEndpoint());
                }
            }

        } catch (Exception e) {
            log.warn("[SigNoz] Could not configure Logback programmatically: {}", e.getMessage());
        }
    }
}
