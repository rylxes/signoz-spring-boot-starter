package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.logging.log4j2.SigNozLog4j2Configurer;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Log4j2 counterpart of {@link SigNozLoggingAutoConfiguration} for Spring Boot 3.x: masked JSON
 * console output, OTLP log export, error fingerprinting and sampling when Log4j2 is the
 * logging backend.
 *
 * <p>Applied only when the Log4j2 API is bound to {@code log4j-core}. When Log4j2 is merely on
 * the classpath but routed to Logback through {@code log4j-to-slf4j} (Spring Boot's default),
 * the context is not a {@code log4j-core} {@link LoggerContext} and nothing is changed.
 */
@Configuration
@ConditionalOnClass(name = "org.apache.logging.log4j.core.LoggerContext")
public class SigNozLog4j2AutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SigNozLog4j2AutoConfiguration.class);

    @Bean
    public SmartInitializingSingleton sigNozLog4j2Configurer(SigNozProperties props,
                                                             MaskingRegistry maskingRegistry) {
        return () -> {
            Object context = LogManager.getContext(false);
            if (!(context instanceof LoggerContext)) {
                return;
            }
            try {
                SigNozLog4j2Configurer.configure((LoggerContext) context, props, maskingRegistry);
            } catch (Exception e) {
                log.warn("[SigNoz] Could not configure Log4j2 programmatically: {}", e.getMessage());
            }
        };
    }
}
