package io.signoz.springboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks that the outbound customizers log query strings masked with the context's
 * {@link MaskingRegistry}, and with the default rules when the context has none.
 */
class SigNozOutboundAutoConfigurationTest {

    private static final String URL = "https://api.example.com/x?tenant=acme&token=q-canary&page=2";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SigNozOutboundAutoConfiguration.class))
            .withBean(SigNozOutboundProperties.class, SigNozOutboundProperties::new);

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
    void restTemplateCustomizerUsesTheContextMaskingRegistry() {
        runner.withBean(MaskingRegistry.class, SigNozOutboundAutoConfigurationTest::tenantMaskingRegistry)
                .run(ctx -> {
                    callThroughRestTemplateInterceptor(ctx.getBean(RestTemplateCustomizer.class));

                    assertThat(lastLine()).contains("?tenant=***&").doesNotContain("acme");
                });
    }

    @Test
    void restTemplateCustomizerMasksWithDefaultsWithoutAMaskingRegistry() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MaskingRegistry.class);
            callThroughRestTemplateInterceptor(ctx.getBean(RestTemplateCustomizer.class));

            assertThat(lastLine()).contains("?tenant=acme&token=***&page=2").doesNotContain("q-canary");
        });
    }

    @Test
    void webClientCustomizerUsesTheContextMaskingRegistry() {
        runner.withBean(MaskingRegistry.class, SigNozOutboundAutoConfigurationTest::tenantMaskingRegistry)
                .run(ctx -> {
                    callThroughWebClient((WebClientCustomizer) ctx.getBean("sigNozWebClientCustomizer"));

                    // The status is read in a way that works on Spring 5 and 6.
                    assertThat(lastLine()).contains("?tenant=***&").contains(" -> 200 in ")
                            .doesNotContain("acme");
                });
    }

    @Test
    void webClientCustomizerMasksWithDefaultsWithoutAMaskingRegistry() {
        runner.run(ctx -> {
            callThroughWebClient((WebClientCustomizer) ctx.getBean("sigNozWebClientCustomizer"));

            assertThat(lastLine()).contains("?tenant=acme&token=***&page=2").doesNotContain("q-canary");
        });
    }

    private static MaskingRegistry tenantMaskingRegistry() {
        SigNozLoggingProperties logging = new SigNozLoggingProperties();
        logging.setMaskedFields(Collections.singletonList("tenant"));
        return new MaskingRegistry(logging);
    }

    private static void callThroughRestTemplateInterceptor(RestTemplateCustomizer customizer) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        customizer.customize(restTemplate);
        assertThat(restTemplate.getInterceptors()).hasSize(1);
        ClientHttpRequestInterceptor interceptor = restTemplate.getInterceptors().get(0);

        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getURI()).thenReturn(URI.create(URL));
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any(byte[].class))).thenReturn(mock(ClientHttpResponse.class));

        interceptor.intercept(request, new byte[0], execution);
    }

    private static void callThroughWebClient(WebClientCustomizer customizer) {
        WebClient.Builder builder = WebClient.builder();
        customizer.customize(builder);
        List<ExchangeFilterFunction> filters = new ArrayList<ExchangeFilterFunction>();
        builder.filters(filters::addAll);
        assertThat(filters).hasSize(1);

        filters.get(0).filter(ClientRequest.create(HttpMethod.GET, URI.create(URL)).build(),
                request -> Mono.just(ClientResponse.create(HttpStatus.OK).build())).block();
    }

    private String lastLine() {
        assertThat(appender.list).isNotEmpty();
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }
}
