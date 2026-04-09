package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.outbound.TracingRestTemplateInterceptor;
import io.signoz.springboot.outbound.TracingWebClientFilter;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateCustomizer;
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
        public RestTemplateCustomizer sigNozRestTemplateCustomizer(SigNozOutboundProperties props) {
            return restTemplate -> restTemplate.getInterceptors().add(new TracingRestTemplateInterceptor(props));
        }
    }

    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    static class WebClientTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "sigNozWebClientCustomizer")
        public Object sigNozWebClientCustomizer(SigNozOutboundProperties props) {
            // WebClientCustomizer adds the tracing filter
            return (org.springframework.boot.web.reactive.function.client.WebClientCustomizer)
                    builder -> builder.filter(new TracingWebClientFilter(props));
        }
    }
}
