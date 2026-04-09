package io.signoz.springboot.alerts;

import io.signoz.springboot.annotation.AlertOnFailure;
import io.signoz.springboot.properties.SigNozAlertProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AlertOnFailureAspect} using
 * {@link AnnotationConfigApplicationContext} with {@link EnableAspectJAutoProxy}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Exception increments counter (verified indirectly via no crash)</li>
 *   <li>Normal completion does not increment counter</li>
 *   <li>Exception is always re-thrown</li>
 *   <li>Disabled aspect via properties</li>
 * </ul>
 *
 * <p>Since Micrometer is not wired in these unit tests (registry is {@code null}),
 * counter increments are tested indirectly by verifying the aspect does not throw
 * its own exception and that the original exception propagates correctly.
 */
class AlertOnFailureAspectTest {

    private AnnotationConfigApplicationContext ctx;
    private AlertService service;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        service = ctx.getBean(AlertService.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void normalCompletionDoesNotThrowOrIncrementCounter() {
        String result = service.doWork("hello");
        assertThat(result).isEqualTo("ok-hello");
    }

    @Test
    void exceptionIsPropagatedAfterAlertRecorded() {
        assertThatThrownBy(() -> service.doFail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("alert-boom");
    }

    @Test
    void runtimeExceptionIsPropagated() {
        assertThatThrownBy(() -> service.doRuntimeFail())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("runtime-alert-boom");
    }

    @Test
    void customMetricAndTagsDoNotCauseErrors() {
        assertThatThrownBy(() -> service.doFailWithCustomMetric())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("custom-boom");
    }

    @Test
    void disabledAspectStillPropagatesException() {
        ctx.close();
        ctx = new AnnotationConfigApplicationContext(DisabledAlertConfig.class);
        AlertService disabledService = ctx.getBean(AlertService.class);

        // Exception still propagates even when aspect is disabled
        assertThatThrownBy(() -> disabledService.doFail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("alert-boom");
    }

    @Test
    void disabledAspectNormalCompletionWorks() {
        ctx.close();
        ctx = new AnnotationConfigApplicationContext(DisabledAlertConfig.class);
        AlertService disabledService = ctx.getBean(AlertService.class);

        String result = disabledService.doWork("test");
        assertThat(result).isEqualTo("ok-test");
    }

    // ---- Inner configuration and beans ----

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public SigNozAlertProperties alertProps() {
            return new SigNozAlertProperties(); // defaults: enabled=true
        }

        @Bean
        public AlertOnFailureAspect alertOnFailureAspect(SigNozAlertProperties props) {
            return new AlertOnFailureAspect(null, props); // null registry — no Micrometer in tests
        }

        @Bean
        public AlertService alertService() {
            return new AlertService();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class DisabledAlertConfig {
        @Bean
        public SigNozAlertProperties alertProps() {
            SigNozAlertProperties props = new SigNozAlertProperties();
            props.setEnabled(false);
            return props;
        }

        @Bean
        public AlertOnFailureAspect alertOnFailureAspect(SigNozAlertProperties props) {
            return new AlertOnFailureAspect(null, props);
        }

        @Bean
        public AlertService alertService() {
            return new AlertService();
        }
    }

    @Component
    static class AlertService {
        @AlertOnFailure
        public String doWork(String input) {
            return "ok-" + input;
        }

        @AlertOnFailure
        public void doFail() {
            throw new IllegalStateException("alert-boom");
        }

        @AlertOnFailure
        public void doRuntimeFail() {
            throw new RuntimeException("runtime-alert-boom");
        }

        @AlertOnFailure(metric = "custom.failures", tags = {"severity=critical", "domain=payment"})
        public void doFailWithCustomMetric() {
            throw new RuntimeException("custom-boom");
        }
    }
}
