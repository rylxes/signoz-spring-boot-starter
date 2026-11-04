package io.signoz.springboot.web;

import io.signoz.springboot.properties.SigNozUserContextProperties;
import io.signoz.springboot.usercontext.UserContextEnricher;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user context comes from Spring Security, so the filter must run after Security's filter
 * chain has authenticated the request. It used to run first, and so never found a user.
 */
class UserContextMdcFilterOrderTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void runsAfterSpringSecuritysFilterChain() {
        int order = OrderUtils.getOrder(UserContextMdcFilter.class, Ordered.LOWEST_PRECEDENCE);
        assertThat(order).isGreaterThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void addsTheUserTheSecurityChainAuthenticated() throws Exception {
        // What Spring Security's chain leaves behind for the filters after it.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
        UserContextMdcFilter filter = new UserContextMdcFilter(new UserContextEnricher(new SigNozUserContextProperties()));
        Map<String, String> seen = new HashMap<String, String>();
        FilterChain handler = (req, res) -> seen.putAll(MDC.getCopyOfContextMap());

        filter.doFilter(new MockHttpServletRequest("GET", "/me"), new MockHttpServletResponse(), handler);

        assertThat(seen).containsEntry("userId", "alice");
        assertThat(seen.get("userRoles")).contains("ROLE_USER");
        assertThat(MDC.get("userId")).as("cleared after the request").isNull();
    }
}
