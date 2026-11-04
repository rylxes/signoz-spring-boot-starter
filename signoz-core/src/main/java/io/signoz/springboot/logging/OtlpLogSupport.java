package io.signoz.springboot.logging;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ResourceAttributes;
import io.signoz.springboot.masking.MaskingRegistry;

import java.util.Map;

/**
 * The OTLP log export and masking logic shared by every SigNoz log appender
 * ({@link OtlpLogbackAppender} and the Log4j2 {@code OtlpLog4j2Appender}), so that
 * the logging backend never changes what leaves the process.
 */
public final class OtlpLogSupport {

    /** Body sent instead of the original when masking it throws. */
    public static final String BODY_WITHHELD = "[signoz:masking-failed:body-withheld]";
    /** Field value sent instead of the original when masking it throws. */
    public static final String FIELD_WITHHELD = "[signoz:masking-failed]";

    /** Where a backend reports export and masking problems (Logback status, Log4j2 StatusLogger). */
    public interface ErrorSink {
        void error(String message, Throwable error);
    }

    private OtlpLogSupport() {
        // utility class
    }

    /**
     * Builds the OTLP logger provider with the service resource attributes, exporting over
     * {@code protocol} ({@code grpc} when {@code null}) to {@code endpoint} exactly as given.
     */
    public static SdkLoggerProvider createLoggerProvider(String endpoint, Map<String, String> headers,
                                                         OtlpExporterSettings.Protocol protocol,
                                                         long exportTimeoutMs, String serviceName,
                                                         String serviceVersion, String environment) {
        LogRecordExporter exporter = OtlpExporterSettings.logExporter(
                protocol == null ? OtlpExporterSettings.Protocol.GRPC : protocol,
                endpoint, headers, exportTimeoutMs);
        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                        ResourceAttributes.SERVICE_NAME, serviceName,
                        ResourceAttributes.SERVICE_VERSION, serviceVersion,
                        AttributeKey.stringKey("deployment.environment"), environment
                )));
        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter).build())
                .build();
    }

    /**
     * Sets the log record's trace context from the event's MDC (the OTel-native
     * {@code trace_id}/{@code span_id}/{@code trace_flags}, else the legacy camelCase keys), so
     * SigNoz and Grafana (Loki → Tempo) can link the log line to its trace natively. Without a
     * valid pair in the MDC the record keeps the SDK default (the current OTel context).
     */
    public static void applyTraceContext(LogRecordBuilder builder, Map<String, String> mdc) {
        SpanContext spanContext = spanContextFromMdc(mdc);
        if (spanContext != null) {
            builder.setContext(Context.root().with(Span.wrap(spanContext)));
        }
    }

    static SpanContext spanContextFromMdc(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return null;
        }
        SpanContext snake = spanContext(mdc.get("trace_id"), mdc.get("span_id"), mdc.get("trace_flags"));
        return snake != null ? snake
                : spanContext(mdc.get("traceId"), mdc.get("spanId"), mdc.get("traceFlags"));
    }

    private static SpanContext spanContext(String traceId, String spanId, String flags) {
        if (traceId == null || spanId == null) {
            return null;
        }
        TraceFlags traceFlags = flags != null && flags.matches("[0-9a-fA-F]{2}")
                ? TraceFlags.fromHex(flags.toLowerCase(java.util.Locale.ROOT), 0) : TraceFlags.getSampled();
        SpanContext ctx = SpanContext.createFromRemoteParent(
                traceId.toLowerCase(java.util.Locale.ROOT), spanId.toLowerCase(java.util.Locale.ROOT),
                traceFlags, TraceState.getDefault());
        return ctx.isValid() ? ctx : null;
    }

    /**
     * Masks a log body before export. {@link MaskingRegistry#maskJsonString(String)} is a
     * superset of {@code maskMessage}: it applies the message-level regex patterns, then masks
     * {@code "field":"value"} pairs, and degrades to plain message masking on free text.
     * Fails closed: if masking throws, the body is withheld rather than exported raw.
     */
    public static String maskBody(MaskingRegistry registry, String body, ErrorSink errors) {
        if (registry == null) {
            return body;
        }
        try {
            return registry.maskJsonString(body);
        } catch (Exception e) {
            errors.error("[SigNoz] Masking failed; withholding log body", e);
            return BODY_WITHHELD;
        }
    }

    /** Masks one context (MDC) value by its key; fails closed like {@link #maskBody}. */
    public static String maskField(MaskingRegistry registry, String key, String value, ErrorSink errors) {
        if (registry == null) {
            return value;
        }
        try {
            return registry.mask(key, value);
        } catch (Exception e) {
            errors.error("[SigNoz] Masking failed for MDC key " + key, e);
            return FIELD_WITHHELD;
        }
    }
}
