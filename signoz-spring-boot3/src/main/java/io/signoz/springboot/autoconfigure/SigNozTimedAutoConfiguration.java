package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.properties.SigNozTimedProperties;
import io.signoz.springboot.timed.TimedAspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures the {@link TimedAspect} bean for Spring Boot 3.x.
 *
 * <p>The aspect is enabled by default and can be disabled with
 * {@code signoz.timed.enabled=false}. A Micrometer {@code MeterRegistry}
 * is injected optionally via {@link ObjectProvider} so the aspect still
 * functions (logging only) when Micrometer is not on the classpath.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.timed.enabled", havingValue = "true", matchIfMissing = true)
public class SigNozTimedAutoConfiguration {

    /**
     * Creates the {@link TimedAspect} bean.
     *
     * @param timedProps configuration properties for the timed feature
     * @param registry   optional Micrometer {@code MeterRegistry}; {@code null} when absent
     * @return a configured {@link TimedAspect}
     */
    @Bean
    @ConditionalOnMissingBean
    public TimedAspect timedAspect(SigNozTimedProperties timedProps,
                                   ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        return new TimedAspect(registry.getIfAvailable(), timedProps);
    }
}
