package io.signoz.springboot.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

/**
 * Thin Spring-managed wrapper around the OpenTelemetry {@link Tracer}.
 *
 * <p>Provides a convenient API for programmatic span creation. For declarative
 * span creation, use the {@link io.signoz.springboot.annotation.Traced} annotation
 * and rely on {@link TracedAspect} instead.
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}Autowired SigNozTracer tracer;
 *
 *   Span span = tracer.startSpan("my-operation");
 *   try (Scope scope = tracer.scope(span)) {
 *       // ... business logic
 *   } finally {
 *       tracer.end(span);
 *   }
 * </pre>
 */
@Component
public class SigNozTracer {

    private final Tracer tracer;

    public SigNozTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("io.signoz.springboot", "1.0.0");
    }

    /**
     * Creates and starts a new span. The caller is responsible for ending it.
     */
    public Span startSpan(String operationName) {
        return tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
    }

    /**
     * Creates and starts a new span with the given kind.
     */
    public Span startSpan(String operationName, SpanKind kind) {
        return tracer.spanBuilder(operationName)
                .setSpanKind(kind)
                .startSpan();
    }

    /**
     * Makes the span the current span and returns a {@link Scope} that must be closed.
     */
    public Scope scope(Span span) {
        return span.makeCurrent();
    }

    /**
     * Ends the span. Must always be called in a {@code finally} block.
     */
    public void end(Span span) {
        if (span != null) {
            span.end();
        }
    }

    /**
     * Records an exception on the current span and marks it as errored.
     */
    public void recordException(Throwable t) {
        Span current = Span.current();
        if (current != null && current.isRecording()) {
            current.recordException(t);
            current.setStatus(StatusCode.ERROR, t.getMessage());
        }
    }

    /**
     * Adds a string attribute to the current span.
     */
    public void setAttribute(String key, String value) {
        Span current = Span.current();
        if (current != null && current.isRecording()) {
            current.setAttribute(AttributeKey.stringKey(key), value);
        }
    }

    /**
     * Adds an event to the current span.
     */
    public void addEvent(String name) {
        Span current = Span.current();
        if (current != null && current.isRecording()) {
            current.addEvent(name);
        }
    }

    /**
     * Adds an event with attributes to the current span.
     */
    public void addEvent(String name, Attributes attributes) {
        Span current = Span.current();
        if (current != null && current.isRecording()) {
            current.addEvent(name, attributes);
        }
    }

    /**
     * Returns the trace ID of the current active span, or {@code "0000000000000000"}
     * if there is no active span.
     */
    public String currentTraceId() {
        Span current = Span.current();
        if (current != null && current.getSpanContext().isValid()) {
            return current.getSpanContext().getTraceId();
        }
        return "0000000000000000";
    }

    /**
     * Returns the span ID of the current active span, or {@code "0000000000000000"}.
     */
    public String currentSpanId() {
        Span current = Span.current();
        if (current != null && current.getSpanContext().isValid()) {
            return current.getSpanContext().getSpanId();
        }
        return "0000000000000000";
    }

    /**
     * Exposes the raw OTel {@link Tracer} for advanced use cases.
     */
    public Tracer getTracer() {
        return tracer;
    }

    /**
     * Exposes a {@link SpanBuilder} for custom span configuration.
     */
    public SpanBuilder spanBuilder(String operationName) {
        return tracer.spanBuilder(operationName);
    }
}
