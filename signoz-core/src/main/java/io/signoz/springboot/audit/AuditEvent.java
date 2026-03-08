package io.signoz.springboot.audit;

import java.time.Instant;
import java.util.Arrays;

/**
 * Immutable model representing a single audit trail entry.
 *
 * <p>Published as a Spring {@code ApplicationEvent} by {@link AuditLogAspect}
 * and consumed by {@link SigNozAuditHandler} (and any other listeners the
 * application registers).
 */
public class AuditEvent {

    /**
     * Outcome of the audited operation.
     */
    public enum Outcome {
        SUCCESS, FAILURE
    }

    private final String traceId;
    private final String spanId;
    private final String actor;
    private final String action;
    private final String resourceType;
    private final String resourceId;
    private final Object[] args;
    private final Object result;
    private final Throwable exception;
    private final Outcome outcome;
    private final Instant timestamp;
    private final String thread;
    private final String className;
    private final String methodName;

    private AuditEvent(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.actor = builder.actor;
        this.action = builder.action;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.args = builder.args;
        this.result = builder.result;
        this.exception = builder.exception;
        this.outcome = builder.outcome;
        this.timestamp = builder.timestamp;
        this.thread = builder.thread;
        this.className = builder.className;
        this.methodName = builder.methodName;
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- Getters ---

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public Object[] getArgs() { return args; }
    public Object getResult() { return result; }
    public Throwable getException() { return exception; }
    public Outcome getOutcome() { return outcome; }
    public Instant getTimestamp() { return timestamp; }
    public String getThread() { return thread; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }

    @Override
    public String toString() {
        return "AuditEvent{" +
                "action='" + action + '\'' +
                ", actor='" + actor + '\'' +
                ", resourceType='" + resourceType + '\'' +
                ", resourceId='" + resourceId + '\'' +
                ", outcome=" + outcome +
                ", traceId='" + traceId + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }

    // --- Builder ---

    public static final class Builder {
        private String traceId;
        private String spanId;
        private String actor;
        private String action;
        private String resourceType = "";
        private String resourceId = "";
        private Object[] args;
        private Object result;
        private Throwable exception;
        private Outcome outcome = Outcome.SUCCESS;
        private Instant timestamp = Instant.now();
        private String thread;
        private String className;
        private String methodName;

        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder spanId(String spanId) { this.spanId = spanId; return this; }
        public Builder actor(String actor) { this.actor = actor; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder resourceType(String resourceType) { this.resourceType = resourceType; return this; }
        public Builder resourceId(String resourceId) { this.resourceId = resourceId; return this; }
        public Builder args(Object[] args) { this.args = args; return this; }
        public Builder result(Object result) { this.result = result; return this; }
        public Builder exception(Throwable exception) { this.exception = exception; return this; }
        public Builder outcome(Outcome outcome) { this.outcome = outcome; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder thread(String thread) { this.thread = thread; return this; }
        public Builder className(String className) { this.className = className; return this; }
        public Builder methodName(String methodName) { this.methodName = methodName; return this; }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
