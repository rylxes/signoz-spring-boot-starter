package io.signoz.springboot.outbound;

import io.signoz.springboot.properties.SigNozOutboundProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TracingRestTemplateInterceptorTest {

    private SigNozOutboundProperties props;
    private ClientHttpRequestExecution execution;
    private ClientHttpResponse mockResponse;
    private org.springframework.http.HttpRequest mockRequest;

    @BeforeEach
    void setUp() throws IOException {
        props = new SigNozOutboundProperties();

        execution = mock(ClientHttpRequestExecution.class);
        mockResponse = mock(ClientHttpResponse.class);
        mockRequest = mock(org.springframework.http.HttpRequest.class);

        when(mockRequest.getMethod()).thenReturn(HttpMethod.GET);
        when(mockRequest.getURI()).thenReturn(URI.create("http://example.com/api/test"));
        when(mockRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);
        when(execution.execute(any(), any(byte[].class))).thenReturn(mockResponse);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesTraceparentFromHttpFilterMdcKeys() throws IOException {
        // TraceIdMdcFilter writes OTel-native snake_case keys when no agent is present.
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        MDC.put("trace_flags", "01");
        MDC.put("requestId", "req-123");

        HttpHeaders headers = new HttpHeaders();
        when(mockRequest.getHeaders()).thenReturn(headers);

        new TracingRestTemplateInterceptor(props).intercept(mockRequest, new byte[0], execution);

        assertThat(headers.getFirst("traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(headers.getFirst("X-Request-ID")).isEqualTo("req-123");
    }

    @Test
    void interceptExecutesRequest() throws IOException {
        TracingRestTemplateInterceptor interceptor = new TracingRestTemplateInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(mockRequest, new byte[0], execution);

        assertThat(response).isNotNull();
        verify(execution).execute(eq(mockRequest), any(byte[].class));
    }

    @Test
    void interceptWorksWhenLoggingDisabled() throws IOException {
        props.setLogRequests(false);
        TracingRestTemplateInterceptor interceptor = new TracingRestTemplateInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(mockRequest, new byte[0], execution);

        assertThat(response).isSameAs(mockResponse);
    }

    @Test
    void interceptWorksWhenHeaderPropagationDisabled() throws IOException {
        props.setPropagateHeaders(false);
        TracingRestTemplateInterceptor interceptor = new TracingRestTemplateInterceptor(props);
        ClientHttpResponse response = interceptor.intercept(mockRequest, new byte[0], execution);

        assertThat(response).isSameAs(mockResponse);
    }
}
