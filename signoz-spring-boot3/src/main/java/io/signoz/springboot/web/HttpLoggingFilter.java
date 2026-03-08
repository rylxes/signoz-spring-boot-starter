package io.signoz.springboot.web;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozWebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs all inbound HTTP requests and their responses as structured entries.
 *
 * <p>Spring Boot 3.x / {@code jakarta.servlet} version.
 *
 * @see io.signoz.springboot.web.HttpLoggingFilter (SB2 counterpart)
 */
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("SIGNOZ_HTTP");

    private final SigNozWebProperties webProps;
    private final MaskingRegistry maskingRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public HttpLoggingFilter(SigNozWebProperties webProps, MaskingRegistry maskingRegistry) {
        this.webProps = webProps;
        this.maskingRegistry = maskingRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : webProps.getExcludePaths()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!webProps.isLogRequests()) {
            filterChain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();
        String method = request.getMethod();

        boolean shouldCacheReqBody = webProps.isLogRequestBody() && isBodyMethod(method);
        CachedBodyRequestWrapper wrappedRequest = shouldCacheReqBody
                ? new CachedBodyRequestWrapper(request, webProps.getMaxBodyBytes())
                : null;
        HttpServletRequest effectiveRequest = wrappedRequest != null ? wrappedRequest : request;

        CachedBodyResponseWrapper wrappedResponse = webProps.isLogResponseBody()
                ? new CachedBodyResponseWrapper(response)
                : null;
        HttpServletResponse effectiveResponse = wrappedResponse != null ? wrappedResponse : response;

        try {
            filterChain.doFilter(effectiveRequest, effectiveResponse);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            if (wrappedResponse != null) {
                wrappedResponse.copyBodyToResponse();
            }
            logRequest(effectiveRequest, effectiveResponse,
                    wrappedRequest, wrappedResponse, duration);
        }
    }

    private void logRequest(HttpServletRequest request,
                            HttpServletResponse response,
                            CachedBodyRequestWrapper wrappedRequest,
                            CachedBodyResponseWrapper wrappedResponse,
                            long durationMs) {

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(request.getMethod()).append(' ').append(request.getRequestURI());

        if (webProps.isLogQueryString() && request.getQueryString() != null) {
            sb.append('?').append(request.getQueryString());
        }

        sb.append(" status=").append(response.getStatus());
        sb.append(" duration=").append(durationMs).append("ms");

        if (webProps.isLogHeaders()) {
            sb.append(" headers=").append(buildHeaderMap(request));
        }

        if (wrappedRequest != null) {
            byte[] body = wrappedRequest.getCachedBody();
            if (body.length > 0) {
                sb.append(" requestBody=")
                        .append(maskingRegistry.maskJsonString(
                                new String(body, StandardCharsets.UTF_8)));
            }
        }

        if (wrappedResponse != null) {
            byte[] body = wrappedResponse.getCapturedBody();
            if (body.length > 0) {
                int maxBytes = webProps.getMaxBodyBytes();
                sb.append(" responseBody=")
                        .append(maskingRegistry.maskJsonString(new String(
                                body, 0, Math.min(body.length, maxBytes), StandardCharsets.UTF_8)));
            }
        }

        log.info(sb.toString());
    }

    private Map<String, String> buildHeaderMap(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, maskingRegistry.mask(name.toLowerCase(), request.getHeader(name)));
            }
        }
        return headers;
    }

    private static boolean isBodyMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }
}
