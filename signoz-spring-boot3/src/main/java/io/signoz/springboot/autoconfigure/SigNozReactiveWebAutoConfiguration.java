package io.signoz.springboot.autoconfigure;

import io.micrometer.context.ContextRegistry;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.webflux.HttpLoggingWebFilter;
import io.signoz.springboot.webflux.MdcRestoringWebFilter;
import io.signoz.springboot.webflux.SigNozMdcThreadLocalAccessor;
import io.signoz.springboot.webflux.TraceIdWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * WebFlux counterpart of {@link SigNozWebAutoConfiguration} for Spring Boot 3.x:
 * registers the reactive trace-ID and HTTP-logging {@code WebFilter}s, and makes the
 * SigNoz MDC follow each request across Reactor thread hops.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SigNozReactiveWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }

    /** Puts the request's IDs back on the thread before the handler, after any filter hopped threads. */
    @Bean
    @ConditionalOnMissingBean
    public MdcRestoringWebFilter mdcRestoringWebFilter() {
        return new MdcRestoringWebFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpLoggingWebFilter httpLoggingWebFilter(SigNozProperties props, MaskingRegistry maskingRegistry) {
        return new HttpLoggingWebFilter(props.getWeb(), maskingRegistry);
    }

    /**
     * Registers {@link SigNozMdcThreadLocalAccessor} and enables Reactor's automatic
     * context propagation (Reactor 3.5.3+), the same switch Spring Boot's
     * {@code spring.reactor.context-propagation=auto} flips. Disable with
     * {@code signoz.web.reactive-context-propagation=false}; the MDC is then present
     * only up to a handler's first thread hop.
     */
    @Configuration
    @ConditionalOnClass(name = "io.micrometer.context.ContextRegistry")
    @ConditionalOnProperty(name = "signoz.web.reactive-context-propagation",
            havingValue = "true", matchIfMissing = true)
    static class ReactorMdcPropagationConfiguration {

        private static final Logger log = LoggerFactory.getLogger(ReactorMdcPropagationConfiguration.class);

        ReactorMdcPropagationConfiguration() {
            // Registering again replaces the accessor with the same key, so repeated
            // application contexts (tests, devtools restarts) stay idempotent.
            ContextRegistry.getInstance().registerThreadLocalAccessor(new SigNozMdcThreadLocalAccessor());
            try {
                Hooks.enableAutomaticContextPropagation();
            } catch (NoSuchMethodError e) {
                log.info("[SigNoz] Reactor {} predates automatic context propagation (3.5.3+); "
                        + "MDC is carried only up to a handler's first thread hop",
                        reactor.core.publisher.Flux.class.getPackage().getImplementationVersion());
            }
        }
    }
}
