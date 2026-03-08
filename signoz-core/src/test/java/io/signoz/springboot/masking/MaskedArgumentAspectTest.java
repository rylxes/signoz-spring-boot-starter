package io.signoz.springboot.masking;

import io.signoz.springboot.annotation.Masked;
import io.signoz.springboot.properties.SigNozLoggingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AOP unit tests for {@link MaskedArgumentAspect}.
 *
 * <p>Uses {@link AnnotationConfigApplicationContext} with {@link EnableAspectJAutoProxy}
 * so Spring's CGLIB proxy is applied to {@link SensitiveService}.
 *
 * <p><strong>Important design note:</strong> Spring CGLIB proxies separate the proxy object
 * from the underlying target instance. Setting a field inside the method body modifies the
 * <em>target</em> object's field, while the test reads the field from the <em>proxy</em>
 * object — which is always {@code null}. To avoid this split, all service methods
 * <em>return</em> the value under test so the return propagates through the AOP chain and
 * back to the caller correctly.
 */
class MaskedArgumentAspectTest {

    private AnnotationConfigApplicationContext ctx;
    private SensitiveService service;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        service = ctx.getBean(SensitiveService.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void fullMaskReplacesAnnotatedParamWithStars() {
        String result = service.login("user1", "MySecret123");
        assertThat(result).isEqualTo("***");
    }

    @Test
    void unannotatedParamPassesThroughUnchanged() {
        String result = service.plainMethod("hello");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void nullAnnotatedValueRemainsNull() {
        // The aspect skips null values (if (args[i] != null) guard in source)
        String result = service.login("user1", null);
        assertThat(result).isNull();
    }

    @Test
    void partialMaskShowsLastChars() {
        String result = service.partialMask("4111111111111234");
        // PartialMaskingStrategy default: show first 2 and last 2 characters
        // "4111111111111234" (16 chars) → "41************34"
        assertThat(result).isNotNull();
        assertThat(result).startsWith("41");
        assertThat(result).endsWith("34");
        assertThat(result).doesNotContain("111111111112");
    }

    @Test
    void onlyAnnotatedParamIsMasked() {
        String[] result = service.mixed("plainArg", "secretArg");
        assertThat(result[0]).isEqualTo("plainArg");
        assertThat(result[1]).isEqualTo("***");
    }

    // ---- Inner configuration and beans ----

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public SigNozLoggingProperties loggingProps() {
            return new SigNozLoggingProperties();
        }

        @Bean
        public MaskingRegistry maskingRegistry(SigNozLoggingProperties props) {
            return new MaskingRegistry(props);
        }

        @Bean
        public MaskedArgumentAspect maskedArgumentAspect(MaskingRegistry registry) {
            return new MaskedArgumentAspect(registry);
        }

        @Bean
        public SensitiveService sensitiveService() {
            return new SensitiveService();
        }
    }

    /**
     * Service under test. Methods <em>return</em> their (potentially masked) parameter
     * value so tests can assert on the return value — avoiding the proxy/target field-split
     * issue that would occur if fields were set inside the method body instead.
     */
    @Component
    static class SensitiveService {

        /** Returns the password argument as received by the method body (after aspect masking). */
        public String login(String user, @Masked String password) {
            return password;
        }

        /** Returns the plain argument, which has no masking annotation. */
        public String plainMethod(String plain) {
            return plain;
        }

        /** Returns the card number as received by the method body (after PARTIAL masking). */
        public String partialMask(@Masked(strategy = Masked.MaskingStrategy.PARTIAL) String card) {
            return card;
        }

        /** Returns both arguments as a two-element array for independent assertion. */
        public String[] mixed(String plain, @Masked String secret) {
            return new String[]{plain, secret};
        }
    }
}
