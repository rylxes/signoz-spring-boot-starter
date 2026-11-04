package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.outbound.TracingRestTemplateInterceptor;
import io.signoz.springboot.outbound.TracingWebClientFilter;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures outbound HTTP tracing for RestTemplate and WebClient.
 */
@Configuration
@ConditionalOnProperty(name = "signoz.outbound.enabled", havingValue = "true", matchIfMissing = true)
public class SigNozOutboundAutoConfiguration {

    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    static class RestTemplateTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "sigNozRestTemplateCustomizer")
        public RestTemplateCustomizer sigNozRestTemplateCustomizer(SigNozOutboundProperties props,
                                                                   ObjectProvider<MaskingRegistry> maskingRegistry) {
            MaskingRegistry registry = maskingRegistry.getIfAvailable();
            return restTemplate -> restTemplate.getInterceptors().add(
                    new TracingRestTemplateInterceptor(props, registry));
        }
    }

    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    static class WebClientTracingConfiguration {

        /**
         * Declared as {@code WebClientCustomizer}, not {@code Object}: Spring Boot collects
         * customizers by type when it creates a {@code WebClient.Builder}, and a bean whose
         * factory method returns {@code Object} is only matched once it has been created. A
         * builder injected into a bean created earlier (any application bean) then missed it, so
         * its WebClient was neither traced nor logged.
         */
        @Bean
        @ConditionalOnMissingBean(name = "sigNozWebClientCustomizer")
        public WebClientCustomizer sigNozWebClientCustomizer(SigNozOutboundProperties props,
                                                             ObjectProvider<MaskingRegistry> maskingRegistry) {
            MaskingRegistry registry = maskingRegistry.getIfAvailable();
            return builder -> builder.filter(new TracingWebClientFilter(props, registry));
        }
    }
}
