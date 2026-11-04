package io.signoz.springboot.logging;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ResourceAttributes;
import io.signoz.springboot.masking.MaskingRegistry;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /** Builds the OTLP gRPC logger provider with the service resource attributes. */
    public static SdkLoggerProvider createLoggerProvider(String endpoint, Map<String, String> headers,
                                                         long exportTimeoutMs, String serviceName,
                                                         String serviceVersion, String environment) {
        OtlpGrpcLogRecordExporterBuilder exporterBuilder = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(exportTimeoutMs, TimeUnit.MILLISECONDS);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                exporterBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                        ResourceAttributes.SERVICE_NAME, serviceName,
                        ResourceAttributes.SERVICE_VERSION, serviceVersion,
                        AttributeKey.stringKey("deployment.environment"), environment
                )));
        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporterBuilder.build()).build())
                .build();
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
