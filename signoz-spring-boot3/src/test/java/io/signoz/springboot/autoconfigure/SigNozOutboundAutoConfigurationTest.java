package io.signoz.springboot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.outbound.TracingRestTemplateInterceptor;
import io.signoz.springboot.outbound.TracingWebClientFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound clients only mask the query strings they log if the auto-configuration hands them
 * the application's {@link MaskingRegistry}; a client built without one logs the query as is.
 */
class SigNozOutboundAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    @Test
    void restTemplateInterceptorReceivesTheMaskingRegistry() {
        runner.run(ctx -> {
            RestTemplate restTemplate = new RestTemplate();
            ctx.getBean("sigNozRestTemplateCustomizer", RestTemplateCustomizer.class).customize(restTemplate);

            TracingRestTemplateInterceptor interceptor = restTemplate.getInterceptors().stream()
                    .filter(TracingRestTemplateInterceptor.class::isInstance)
                    .map(TracingRestTemplateInterceptor.class::cast)
                    .findFirst().orElseThrow(AssertionError::new);
            assertThat(ReflectionTestUtils.getField(interceptor, "maskingRegistry"))
                    .isSameAs(ctx.getBean(MaskingRegistry.class));
        });
    }

    @Test
    void webClientFilterReceivesTheMaskingRegistry() {
        runner.run(ctx -> {
            WebClient.Builder builder = WebClient.builder();
            ((WebClientCustomizer) ctx.getBean("sigNozWebClientCustomizer")).customize(builder);

            List<ExchangeFilterFunction> filters = new ArrayList<ExchangeFilterFunction>();
            builder.filters(filters::addAll);
            TracingWebClientFilter filter = filters.stream()
                    .filter(TracingWebClientFilter.class::isInstance)
                    .map(TracingWebClientFilter.class::cast)
                    .findFirst().orElseThrow(AssertionError::new);
            assertThat(ReflectionTestUtils.getField(filter, "maskingRegistry"))
                    .isSameAs(ctx.getBean(MaskingRegistry.class));
        });
    }
}
