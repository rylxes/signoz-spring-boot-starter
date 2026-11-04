package io.signoz.springboot.usercontext;

import io.signoz.springboot.properties.SigNozUserContextProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link UserContextEnricher}.
 */
class UserContextEnricherTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void enrichMdcDoesNothingWhenDisabled() {
        SigNozUserContextProperties props = new SigNozUserContextProperties();
        props.setEnabled(false);
        UserContextEnricher enricher = new UserContextEnricher(props);

        enricher.enrichMdc();

        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("userEmail")).isNull();
        assertThat(MDC.get("userRoles")).isNull();
    }

    @Test
    void enrichMdcHandlesMissingSecurityContext() {
        // Spring Security is not on the test classpath (or no authentication set),
        // so enrichMdc should not throw and should leave MDC empty
        SigNozUserContextProperties props = new SigNozUserContextProperties();
        UserContextEnricher enricher = new UserContextEnricher(props);

        enricher.enrichMdc();

        // Should not throw; MDC should remain empty because there is no security context
        // (SecurityContextHolder may or may not be available depending on test classpath)
        // The key assertion is that no exception is thrown
    }

    @Test
    void clearMdcRemovesAllKeys() {
        MDC.put("userId", "test-user");
        MDC.put("userEmail", "test@example.com");
        MDC.put("userRoles", "ROLE_ADMIN");

        SigNozUserContextProperties props = new SigNozUserContextProperties();
        UserContextEnricher enricher = new UserContextEnricher(props);

        enricher.clearMdc();

        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("userEmail")).isNull();
        assertThat(MDC.get("userRoles")).isNull();
    }

    @Test
    void clearMdcDoesNotAffectOtherKeys() {
        MDC.put("userId", "test-user");
        MDC.put("traceId", "abc123");

        SigNozUserContextProperties props = new SigNozUserContextProperties();
        UserContextEnricher enricher = new UserContextEnricher(props);

        enricher.clearMdc();

        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("abc123");
    }

    @Test
    void enrichMdcDoesNotThrowWhenSecurityNotOnClasspath() {
        SigNozUserContextProperties props = new SigNozUserContextProperties();
        props.setExtractEmail(true);
        props.setExtractRoles(true);
        UserContextEnricher enricher = new UserContextEnricher(props);

        // Should complete without exception regardless of classpath
        enricher.enrichMdc();
    }

    @Test
    void defaultPropertiesAreCorrect() {
        SigNozUserContextProperties props = new SigNozUserContextProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isExtractEmail()).isTrue();
        assertThat(props.isExtractRoles()).isTrue();
        assertThat(props.getPrincipalField()).isEqualTo("email");
    }

    @Test
    void aUserDetailsPrincipalIsIdentifiedByItsUsername() {
        // Spring Security's own User has neither getEmail() nor getName(): its userId used to be
        // its whole toString(), authorities included.
        User alice = new User("alice", "pw", AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_AUDITOR"));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(alice, null, alice.getAuthorities());

        Map<String, String> entries = new UserContextEnricher(new SigNozUserContextProperties()).extract(auth);

        assertThat(entries).containsEntry(UserContextEnricher.MDC_USER_ID, "alice");
        assertThat(entries.get(UserContextEnricher.MDC_USER_ROLES)).contains("ROLE_USER").contains("ROLE_AUDITOR");
        assertThat(entries.values()).noneMatch(v -> v.contains("Password") || v.contains("Granted Authorities"));
    }

    @Test
    void theConfiguredPrincipalFieldIsUsedWhenThePrincipalHasIt() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new EmailPrincipal("alice@example.com"), null, AuthorityUtils.createAuthorityList("ROLE_USER"));

        Map<String, String> entries = new UserContextEnricher(new SigNozUserContextProperties()).extract(auth);

        assertThat(entries).containsEntry(UserContextEnricher.MDC_USER_ID, "alice@example.com")
                .containsEntry(UserContextEnricher.MDC_USER_EMAIL, "alice@example.com");
    }

    @Test
    void aStringPrincipalIsItsOwnName() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "bob", null, AuthorityUtils.createAuthorityList("ROLE_USER"));

        assertThat(new UserContextEnricher(new SigNozUserContextProperties()).extract(auth))
                .containsEntry(UserContextEnricher.MDC_USER_ID, "bob");
    }

    /** A custom principal exposing an email, as many applications' user classes do. */
    public static class EmailPrincipal {
        private final String email;

        EmailPrincipal(String email) {
            this.email = email;
        }

        public String getEmail() {
            return email;
        }
    }
}
