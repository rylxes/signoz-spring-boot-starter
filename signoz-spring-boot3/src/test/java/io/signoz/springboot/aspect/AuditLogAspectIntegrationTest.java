package io.signoz.springboot.aspect;

import io.signoz.springboot.TestSigNozApplication;
import io.signoz.springboot.annotation.AuditLog;
import io.signoz.springboot.audit.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@link io.signoz.springboot.audit.AuditLogAspect}.
 *
 * <p>Verifies end-to-end that when a method annotated with {@link AuditLog}
 * is called on a Spring-managed bean, the aspect publishes an {@link AuditEvent}
 * to the application context with the correct action name and outcome.
 *
 * <p>An inner {@link AuditEventCapture} listener bean collects all received events
 * so tests can assert on them. An inner {@link AuditedService} bean provides
 * the annotated methods under test.
 */
@SpringBootTest(classes = {
        TestSigNozApplication.class,
        AuditLogAspectIntegrationTest.TestBeans.class
})
@ActiveProfiles("test")
class AuditLogAspectIntegrationTest {

    /**
     * Inner test configuration that registers the {@link AuditedService} and
     * the {@link AuditEventCapture} listener as Spring beans.
     */
    @TestConfiguration
    static class TestBeans {

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
     * A Spring-managed component whose methods are annotated with {@link AuditLog}
     * so that the aspect intercepts them during tests.
     */
    @Component
    static class AuditedService {

        @AuditLog(action = "DO_SOMETHING", resourceType = "TestResource")
        public String doSomething(String input) {
            return "result-" + input;
        }

        @AuditLog(action = "DO_FAIL", resourceType = "TestResource")
        public String doFail(String input) {
            throw new IllegalStateException("simulated failure");
        }
    }

    /**
     * Event listener that accumulates every {@link AuditEvent} published during
     * the test run. Tests use the captured events for assertions.
     *
     * <p>Uses {@code @EventListener} (method-level annotation) rather than
     * {@code implements ApplicationListener} because {@link AuditEvent} is a plain
     * POJO and does not extend {@code ApplicationEvent}.
     */
    @Component
    static class AuditEventCapture {

        private final List<AuditEvent> events = new ArrayList<>();

        @EventListener
        public void handle(AuditEvent event) {
            events.add(event);
        }

        public List<AuditEvent> getEvents() {
            return events;
        }

        public void clear() {
            events.clear();
        }
    }

    @Autowired
    private AuditedService auditedService;

    @Autowired
    private AuditEventCapture auditEventCapture;

    @BeforeEach
    void clearEvents() {
        auditEventCapture.clear();
    }

    @Test
    void auditLogAnnotationPublishesEvent() {
        auditedService.doSomething("test-input");

        List<AuditEvent> events = auditEventCapture.getEvents();
        assertThat(events).hasSize(1);

        AuditEvent event = events.get(0);
        assertThat(event.getAction()).isEqualTo("DO_SOMETHING");
        assertThat(event.getResourceType()).isEqualTo("TestResource");
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
    }

    @Test
    void throwingMethodPublishesFailureEvent() {
        try {
            auditedService.doFail("bad-input");
        } catch (IllegalStateException expected) {
            // expected — the aspect should still publish a FAILURE event
        }

        List<AuditEvent> events = auditEventCapture.getEvents();
        assertThat(events).hasSize(1);

        AuditEvent event = events.get(0);
        assertThat(event.getAction()).isEqualTo("DO_FAIL");
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.FAILURE);
        assertThat(event.getException()).isNotNull();
        assertThat(event.getException().getMessage()).isEqualTo("simulated failure");
    }
}
