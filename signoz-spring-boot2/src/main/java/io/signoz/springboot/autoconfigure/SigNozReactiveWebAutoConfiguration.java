package io.signoz.springboot.autoconfigure;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.webflux.HttpLoggingWebFilter;
import io.signoz.springboot.webflux.TraceIdWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebFlux counterpart of {@link SigNozWebAutoConfiguration} for Spring Boot 2.x:
 * registers the reactive trace-ID and HTTP-logging {@code WebFilter}s.
 *
 * <p>Spring Boot 2.7 ships Reactor 3.4, which has no automatic context propagation,
 * so the SigNoz MDC is on the thread only up to a handler's first asynchronous
 * boundary. Past it, the entries are still in the Reactor context: outbound
 * {@code WebClient} calls propagate them, and handlers can log with
 * {@code ReactiveMdc.onNext(...)} or {@code ReactiveMdc.runWithMdc(...)}.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SigNozReactiveWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLoggingWebFilter httpLoggingWebFilter(SigNozProperties props, MaskingRegistry maskingRegistry) {
        return new HttpLoggingWebFilter(props.getWeb(), maskingRegistry);
    }
}
