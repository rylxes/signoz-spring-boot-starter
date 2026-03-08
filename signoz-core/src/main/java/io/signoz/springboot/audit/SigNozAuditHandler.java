package io.signoz.springboot.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Default {@link AuditEvent} handler that writes a structured audit log entry
 * via SLF4J using the {@code AUDIT} marker.
 *
 * <p>The structured log entry contains all relevant audit fields so that SigNoz
 * can index and search them:
 * <ul>
 *   <li>{@code audit.action}, {@code audit.actor}, {@code audit.outcome}</li>
 *   <li>{@code audit.resourceType}, {@code audit.resourceId}</li>
 *   <li>{@code traceId}, {@code spanId} — from the active OTel context</li>
 * </ul>
 *
 * <p>Applications can disable this handler and provide their own by excluding
 * {@code SigNozAuditAutoConfiguration} or by listening to {@link AuditEvent}
 * directly.
 */
@Component
public class SigNozAuditHandler {

    private static final Logger log = LoggerFactory.getLogger("SIGNOZ_AUDIT");
    private static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");

    @EventListener
    public void onAuditEvent(AuditEvent event) {
        if (event.getOutcome() == AuditEvent.Outcome.SUCCESS) {
            log.info(AUDIT,
                    "AUDIT action={} actor={} resourceType={} resourceId={} outcome={} traceId={} spanId={} class={} method={}{}",
                    event.getAction(),
                    event.getActor(),
                    event.getResourceType(),
                    event.getResourceId(),
                    event.getOutcome(),
                    event.getTraceId(),
                    event.getSpanId(),
                    event.getClassName(),
                    event.getMethodName(),
                    formatArgs(event));
        } else {
            log.warn(AUDIT,
                    "AUDIT action={} actor={} resourceType={} resourceId={} outcome={} traceId={} spanId={} class={} method={} error={}{}",
                    event.getAction(),
                    event.getActor(),
                    event.getResourceType(),
                    event.getResourceId(),
                    event.getOutcome(),
                    event.getTraceId(),
                    event.getSpanId(),
                    event.getClassName(),
                    event.getMethodName(),
                    event.getException() != null ? event.getException().getMessage() : "unknown",
                    formatArgs(event));
        }
    }

    private String formatArgs(AuditEvent event) {
        if (event.getArgs() == null || event.getArgs().length == 0) {
            return "";
        }
        return " args=" + Arrays.toString(event.getArgs());
    }
}
