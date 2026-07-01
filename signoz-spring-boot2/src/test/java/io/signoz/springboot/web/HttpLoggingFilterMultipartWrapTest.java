package io.signoz.springboot.web;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import io.signoz.springboot.properties.SigNozWebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the request-wrapping decision in {@link HttpLoggingFilter}.
 *
 * <p>The body-caching {@link CachedBodyRequestWrapper} must NOT be interposed on
 * multipart requests: caching the body interferes with servlet multipart part
 * parsing ({@code getParts()}), which strips file parts and causes
 * {@code MissingServletRequestPartException} downstream. Non-multipart POST/PUT/PATCH
 * bodies must still be cached so request-body logging keeps working.
 */
class HttpLoggingFilterMultipartWrapTest {

    private final HttpLoggingFilter filter = new HttpLoggingFilter(
            new SigNozWebProperties(),
            new MaskingRegistry(new SigNozLoggingProperties()));

    private ServletRequest requestPassedDownstream(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> passed = new AtomicReference<>();
        FilterChain chain = (req, res) -> passed.set(req);
        filter.doFilter(request, response, chain);
        return passed.get();
    }

    @Test
    public void multipartRequestIsNotBodyCached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContentType("multipart/form-data; boundary=abc123");
        request.setContent("--abc123\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\ncsv\r\n--abc123--".getBytes());

        ServletRequest downstream = requestPassedDownstream(request);

        assertThat(downstream)
                .as("multipart request must NOT be wrapped in the body-caching wrapper")
                .isNotInstanceOf(CachedBodyRequestWrapper.class)
                .isSameAs(request);
    }

    @Test
    public void nonMultipartPostIsStillBodyCached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api");
        request.setContentType("application/json");
        request.setContent("{\"a\":1}".getBytes());

        ServletRequest downstream = requestPassedDownstream(request);

        assertThat(downstream)
                .as("non-multipart POST body must still be cached for request-body logging")
                .isInstanceOf(CachedBodyRequestWrapper.class);
    }
}
