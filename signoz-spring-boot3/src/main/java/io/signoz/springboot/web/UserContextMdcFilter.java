package io.signoz.springboot.web;

import io.signoz.springboot.usercontext.UserContextEnricher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enriches the SLF4J MDC with user context information
 * extracted from Spring Security for the duration of each HTTP request.
 *
 * <p>Spring Boot 3.x / {@code jakarta.servlet} version.
 *
 * <p>Runs at {@code HIGHEST_PRECEDENCE + 2}, immediately after the
 * {@code TraceIdMdcFilter} (which runs at {@code HIGHEST_PRECEDENCE + 1}),
 * so that trace IDs are already in the MDC when user context is added.
 *
 * @see io.signoz.springboot.usercontext.UserContextEnricher
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class UserContextMdcFilter extends OncePerRequestFilter {

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
