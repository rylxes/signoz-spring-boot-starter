package io.signoz.springboot.audit;

import io.signoz.springboot.annotation.AuditLog;
import io.signoz.springboot.properties.SigNozAuditProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuditLogAspect} using {@link AnnotationConfigApplicationContext}
 * with {@link EnableAspectJAutoProxy}.
 *
 * <p>Rather than mocking {@link ApplicationEventPublisher} (which Spring replaces with
 * the application context itself as a resolvable dependency), tests use a real
 * {@link AuditEventCapture} listener that collects every published {@link AuditEvent}.
 */
class AuditLogAspectTest {

    private AnnotationConfigApplicationContext ctx;
    private AuditedService service;
    private AuditEventCapture eventCapture;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        service = ctx.getBean(AuditedService.class);
        eventCapture = ctx.getBean(AuditEventCapture.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void successfulMethodPublishesAuditEventWithSuccessOutcome() {
        service.doWork("hello");

        assertThat(eventCapture.events).hasSize(1);
        AuditEvent event = eventCapture.events.get(0);
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
        assertThat(event.getAction()).isEqualTo("TEST_ACTION");
    }

    @Test
    void throwingMethodPublishesFailureEventAndRethrows() {
        assertThatThrownBy(() -> service.doFail())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("intentional");

        assertThat(eventCapture.events).hasSize(1);
        AuditEvent event = eventCapture.events.get(0);
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.FAILURE);
        assertThat(event.getException()).isNotNull();
        assertThat(event.getException().getMessage()).isEqualTo("intentional");
    }

    @Test
    void captureArgsFalseResultsInNullArgs() {
        service.doNoArgs("secretValue");

        assertThat(eventCapture.events).hasSize(1);
        assertThat(eventCapture.events.get(0).getArgs()).isNull();
    }

    @Test
    void captureArgsDefaultResultsInNonNullArgs() {
        service.doWork("hello");

        assertThat(eventCapture.events).hasSize(1);
        assertThat(eventCapture.events.get(0).getArgs()).isNotNull();
    }

    @Test
    void aspectDisabledWhenAuditPropsDisabled() {
        ctx.close();
        ctx = new AnnotationConfigApplicationContext(DisabledAuditConfig.class);
        AuditedService disabledService = ctx.getBean(AuditedService.class);
        AuditEventCapture capture = ctx.getBean(AuditEventCapture.class);

        disabledService.doWork("hello");
        assertThat(capture.events).isEmpty();
    }

    @Test
    void eventIncludesClassName() {
        service.doWork("hello");

        assertThat(eventCapture.events).hasSize(1);
        assertThat(eventCapture.events.get(0).getClassName()).contains("AuditedService");
    }

    @Test
    void eventIncludesMethodName() {
        service.doWork("hello");

        assertThat(eventCapture.events).hasSize(1);
        assertThat(eventCapture.events.get(0).getMethodName()).isEqualTo("doWork");
    }

    // ---- Inner configuration and beans ----

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public SigNozAuditProperties auditProps() {
            return new SigNozAuditProperties(); // defaults: enabled=true, captureArgs=true
        }

        @Bean
        public AuditLogAspect auditLogAspect(ApplicationEventPublisher pub,
                                              SigNozAuditProperties props) {
            return new AuditLogAspect(pub, props);
        }

        @Bean
        public AuditedService auditedService() {
            return new AuditedService();
        }

        @Bean
        public AuditEventCapture auditEventCapture() {
            return new AuditEventCapture();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class DisabledAuditConfig {
        @Bean
        public SigNozAuditProperties auditProps() {
            SigNozAuditProperties p = new SigNozAuditProperties();
            p.setEnabled(false);
            return p;
        }

        @Bean
        public AuditLogAspect auditLogAspect(ApplicationEventPublisher pub,
                                              SigNozAuditProperties props) {
            return new AuditLogAspect(pub, props);
        }

        @Bean
        public AuditedService auditedService() {
            return new AuditedService();
        }

        @Bean
        public AuditEventCapture auditEventCapture() {
            return new AuditEventCapture();
        }
    }

    /**
     * Simple event listener bean that collects every {@link AuditEvent} published
     * during the test so assertions can be made against them.
     *
     * <p>Uses {@code @EventListener} (method-level) rather than
     * {@code implements ApplicationListener} because {@link AuditEvent} is a plain
     * POJO and does not extend {@code ApplicationEvent}.
     */
    static class AuditEventCapture {
        final List<AuditEvent> events = new ArrayList<AuditEvent>();

        @EventListener
        public void handle(AuditEvent event) {
            events.add(event);
        }
    }

    @Component
    static class AuditedService {
        @AuditLog(action = "TEST_ACTION", resourceType = "TestResource")
        public String doWork(String input) {
            return "result-" + input;
        }

        @AuditLog(action = "FAIL_ACTION")
        public void doFail() {
            throw new RuntimeException("intentional");
        }

        @AuditLog(action = "NO_ARGS", captureArgs = false)
        public String doNoArgs(String secret) {
            return "ok";
        }
    }
}
