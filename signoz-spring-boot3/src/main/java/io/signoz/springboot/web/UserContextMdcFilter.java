package io.signoz.springboot.web;

import io.signoz.springboot.usercontext.UserContextEnricher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enriches the SLF4J MDC with user context information
 * extracted from Spring Security for the duration of each HTTP request.
 *
 * <p>Spring Boot 3.x / {@code jakarta.servlet} version.
 *
 * <p>Runs at order {@value #ORDER}, after Spring Security's filter chain (order -100 by
 * default), because that chain is what authenticates the request and fills the
 * {@code SecurityContext}: HTTP Basic, bearer tokens and form logins alike. Running earlier, as
 * this filter once did, it always found an unauthenticated context and added nothing. It also
 * runs after {@code TraceIdMdcFilter}, so trace IDs are already in the MDC.
 *
 * @see io.signoz.springboot.usercontext.UserContextEnricher
 */
@Order(UserContextMdcFilter.ORDER)
public class UserContextMdcFilter extends OncePerRequestFilter {

    /** After Spring Security's filter chain ({@code SecurityProperties.DEFAULT_FILTER_ORDER}, -100). */
    public static final int ORDER = 0;

    private final UserContextEnricher enricher;

    /**
     * Creates a new filter backed by the given enricher.
     *
     * @param enricher the user context enricher
     */
    public UserContextMdcFilter(UserContextEnricher enricher) {
        this.enricher = enricher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        enricher.enrichMdc();
        try {
            filterChain.doFilter(request, response);
        } finally {
            enricher.clearMdc();
        }
    }
}
