package io.signoz.springboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.OpenTelemetry;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.outbound.TracingRestTemplateInterceptor;
import io.signoz.springboot.outbound.TracingWebClientFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound clients only mask the query strings they log if the auto-configuration hands them
 * the application's {@link MaskingRegistry}; a client built without one falls back to the default
 * rules and ignores {@code signoz.logging.*} customisations.
 */
class SigNozOutboundAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
            .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
            .withPropertyValues("signoz.tracing.enabled=false");

    private ListAppender<ILoggingEvent> appender;
    private Logger outboundLogger;

    @BeforeEach
    void attachAppender() {
        outboundLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_OUTBOUND");
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        outboundLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        outboundLogger.detachAppender(appender);
    }

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

    /**
     * Uses real responses rather than mocks, so a status method missing from this module's Spring
     * version fails here with {@code NoSuchMethodError} instead of being stubbed away.
     */
    @Test
    void restTemplateInterceptorLogsTheResponseStatus() {
        runner.run(ctx -> {
            RestTemplate restTemplate = new RestTemplate();
            ctx.getBean("sigNozRestTemplateCustomizer", RestTemplateCustomizer.class).customize(restTemplate);
            TracingRestTemplateInterceptor interceptor = restTemplate.getInterceptors().stream()
                    .filter(TracingRestTemplateInterceptor.class::isInstance)
                    .map(TracingRestTemplateInterceptor.class::cast)
                    .findFirst().orElseThrow(AssertionError::new);

            ClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.CREATED);
            interceptor.intercept(new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/x")),
                    new byte[0], (request, body) -> response);

            assertThat(lastLine()).contains("Outbound GET https://api.example.com/x -> 201 in ");
        });
    }

    @Test
    void webClientFilterLogsTheResponseStatus() {
        runner.run(ctx -> {
            WebClient.Builder builder = WebClient.builder();
            ((WebClientCustomizer) ctx.getBean("sigNozWebClientCustomizer")).customize(builder);
            List<ExchangeFilterFunction> filters = new ArrayList<ExchangeFilterFunction>();
            builder.filters(filters::addAll);
            TracingWebClientFilter filter = filters.stream()
                    .filter(TracingWebClientFilter.class::isInstance)
                    .map(TracingWebClientFilter.class::cast)
                    .findFirst().orElseThrow(AssertionError::new);

            filter.filter(ClientRequest.create(HttpMethod.GET, URI.create("https://api.example.com/x")).build(),
                    request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build())).block();

            assertThat(lastLine()).contains("Outbound GET https://api.example.com/x -> 202 in ");
        });
    }

    private String lastLine() {
        assertThat(appender.list).isNotEmpty();
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }
}
