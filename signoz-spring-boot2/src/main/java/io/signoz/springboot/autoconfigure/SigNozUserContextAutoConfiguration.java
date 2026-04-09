package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.properties.SigNozUserContextProperties;
import io.signoz.springboot.usercontext.UserContextEnricher;
import io.signoz.springboot.web.UserContextMdcFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures user context enrichment beans for Spring Boot 2.x.
 *
 * <p>Activated when Spring Security is on the classpath and
 * {@code signoz.user-context.enabled} is {@code true} (default).
 */
@Configuration
@ConditionalOnProperty(name = "signoz.user-context.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
@EnableConfigurationProperties(SigNozUserContextProperties.class)
public class SigNozUserContextAutoConfiguration {

    /**
     * Creates the {@link UserContextEnricher} bean.
     *
     * @param properties the user context properties
     * @return a new enricher instance
     */
    @Bean
    @ConditionalOnMissingBean
    public UserContextEnricher userContextEnricher(SigNozUserContextProperties properties) {
        return new UserContextEnricher(properties);
    }

    /**
     * Creates the {@link UserContextMdcFilter} bean.
     *
     * @param enricher the user context enricher
     * @return a new filter instance
     */
    @Bean
    @ConditionalOnMissingBean
    public UserContextMdcFilter userContextMdcFilter(UserContextEnricher enricher) {
        return new UserContextMdcFilter(enricher);
    }
}
