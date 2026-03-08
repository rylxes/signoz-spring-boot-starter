package io.signoz.springboot.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SigNozAuditHandlerTest {

    private SigNozAuditHandler handler;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        handler = new SigNozAuditHandler();
        auditLogger = (Logger) LoggerFactory.getLogger("SIGNOZ_AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
        auditLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
    }

    @Test
    void successEventLogsAtInfoLevel() {
        handler.onAuditEvent(successEvent("SAVE_USER"));
        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void failureEventLogsAtWarnLevel() {
        handler.onAuditEvent(failureEvent("DELETE_USER"));
        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void loggedMessageContainsAction() {
        handler.onAuditEvent(successEvent("PROCESS_PAYMENT"));
        assertThat(listAppender.list.get(0).getFormattedMessage()).contains("PROCESS_PAYMENT");
    }

    @Test
    void loggedMessageContainsActor() {
        AuditEvent event = AuditEvent.builder().action("OP").actor("admin-user")
                .outcome(AuditEvent.Outcome.SUCCESS).build();
        handler.onAuditEvent(event);
        assertThat(listAppender.list.get(0).getFormattedMessage()).contains("admin-user");
    }

    @Test
    void loggedMessageContainsOutcome() {
        handler.onAuditEvent(successEvent("OP"));
        assertThat(listAppender.list.get(0).getFormattedMessage()).contains("SUCCESS");
    }

    @Test
    void auditMarkerIsPresentOnLogEntry() {
        handler.onAuditEvent(successEvent("OP"));
        ILoggingEvent logged = listAppender.list.get(0);
        // Logback 1.2.x uses getMarker() (single), not getMarkerList() (added in 1.3+)
        assertThat(logged.getMarker()).isNotNull();
        assertThat(logged.getMarker().getName()).isEqualTo("AUDIT");
    }

    @Test
    void nullArgsDoesNotCauseNpe() {
        AuditEvent event = AuditEvent.builder().action("OP").args(null)
                .outcome(AuditEvent.Outcome.SUCCESS).build();
        handler.onAuditEvent(event); // must not throw
        assertThat(listAppender.list).hasSize(1);
    }

    @Test
    void nonNullArgsAppearsInMessage() {
        AuditEvent event = AuditEvent.builder().action("OP")
                .args(new Object[]{"user123"})
                .outcome(AuditEvent.Outcome.SUCCESS).build();
        handler.onAuditEvent(event);
        assertThat(listAppender.list.get(0).getFormattedMessage()).contains("user123");
    }

    private AuditEvent successEvent(String action) {
        return AuditEvent.builder().action(action).actor("test-actor")
                .outcome(AuditEvent.Outcome.SUCCESS).build();
    }

    private AuditEvent failureEvent(String action) {
        return AuditEvent.builder().action(action).actor("test-actor")
                .outcome(AuditEvent.Outcome.FAILURE)
                .exception(new RuntimeException("oops")).build();
    }
}
