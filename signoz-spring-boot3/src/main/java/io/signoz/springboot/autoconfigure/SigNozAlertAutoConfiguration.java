package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.alerts.AlertOnFailureAspect;
import io.signoz.springboot.properties.SigNozAlertProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures the {@link AlertOnFailureAspect} bean for Spring Boot 3.x.
 *
 * <p>The aspect is enabled by default and can be disabled with
 * {@code signoz.alerts.enabled=false}. A Micrometer {@code MeterRegistry}
 * is injected optionally via {@link ObjectProvider}; the aspect still
 * functions (logging only) when Micrometer is not on the classpath.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.alerts.enabled", havingValue = "true", matchIfMissing = true)
public class SigNozAlertAutoConfiguration {

    private static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";

    // Separate classes rather than one bean method: resolving a method whose signature names
    // MeterRegistry throws TypeNotPresentException when Micrometer is absent, even via ObjectProvider.
    @Configuration
    @ConditionalOnClass(name = METER_REGISTRY)
    static class WithMicrometer {

        /**
         * Creates the {@link AlertOnFailureAspect} bean.
         *
         * @param registry   optional Micrometer {@code MeterRegistry}: the only or primary one, else {@code null}
         * @param alertProps configuration properties for the alert feature
         * @return a configured {@link AlertOnFailureAspect}
         */
        @Bean
        @ConditionalOnMissingBean
        public AlertOnFailureAspect alertOnFailureAspect(
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry,
                SigNozAlertProperties alertProps) {
            return new AlertOnFailureAspect(registry.getIfUnique(), alertProps);
        }
    }

    @Configuration
    @ConditionalOnMissingClass(METER_REGISTRY)
    static class WithoutMicrometer {

        /**
         * Creates a logging-only {@link AlertOnFailureAspect} bean.
         *
         * @param alertProps configuration properties for the alert feature
         * @return a configured {@link AlertOnFailureAspect}
         */
        @Bean
        @ConditionalOnMissingBean
        public AlertOnFailureAspect alertOnFailureAspect(SigNozAlertProperties alertProps) {
            return new AlertOnFailureAspect(null, alertProps);
        }
    }
}
