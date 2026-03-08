package io.signoz.springboot.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventTest {

    @Test
    void builderProducesCorrectFields() {
        Object[] args = new Object[]{"arg1", "arg2"};
        AuditEvent event = AuditEvent.builder()
                .traceId("trace-1")
                .spanId("span-1")
                .actor("admin")
                .action("USER_CREATED")
                .resourceType("User")
                .resourceId("42")
                .args(args)
                .result("ok")
                .outcome(AuditEvent.Outcome.SUCCESS)
                .className("UserService")
                .methodName("createUser")
                .build();

        assertThat(event.getTraceId()).isEqualTo("trace-1");
        assertThat(event.getSpanId()).isEqualTo("span-1");
        assertThat(event.getActor()).isEqualTo("admin");
        assertThat(event.getAction()).isEqualTo("USER_CREATED");
        assertThat(event.getResourceType()).isEqualTo("User");
        assertThat(event.getResourceId()).isEqualTo("42");
        assertThat(event.getArgs()).isEqualTo(args);
        assertThat(event.getResult()).isEqualTo("ok");
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
        assertThat(event.getClassName()).isEqualTo("UserService");
        assertThat(event.getMethodName()).isEqualTo("createUser");
    }

    @Test
    void defaultOutcomeIsSuccess() {
        AuditEvent event = AuditEvent.builder().action("OP").build();
        assertThat(event.getOutcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
    }

    @Test
    void defaultResourceTypeIsEmptyString() {
        AuditEvent event = AuditEvent.builder().action("OP").build();
        assertThat(event.getResourceType()).isEmpty();
    }

    @Test
    void defaultResourceIdIsEmptyString() {
        AuditEvent event = AuditEvent.builder().action("OP").build();
        assertThat(event.getResourceId()).isEmpty();
    }

    @Test
    void defaultTimestampIsNotNull() {
        AuditEvent event = AuditEvent.builder().action("OP").build();
        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void toStringContainsActionAndOutcome() {
        AuditEvent event = AuditEvent.builder()
                .action("PAYMENT_PROCESSED")
                .outcome(AuditEvent.Outcome.FAILURE)
                .build();
        String s = event.toString();
        assertThat(s).contains("PAYMENT_PROCESSED");
        assertThat(s).contains("FAILURE");
    }

    @Test
    void outcomeEnumHasSuccessAndFailure() {
        assertThat(AuditEvent.Outcome.values()).hasSize(2);
        assertThat(AuditEvent.Outcome.values()).contains(
                AuditEvent.Outcome.SUCCESS, AuditEvent.Outcome.FAILURE);
    }

    @Test
    void exceptionCanBeSetAndRetrieved() {
        RuntimeException ex = new RuntimeException("test error");
        AuditEvent event = AuditEvent.builder()
                .action("OP")
                .exception(ex)
                .outcome(AuditEvent.Outcome.FAILURE)
                .build();
        assertThat(event.getException()).isSameAs(ex);
        assertThat(event.getException().getMessage()).isEqualTo("test error");
    }
}
