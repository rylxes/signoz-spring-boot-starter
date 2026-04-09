package io.signoz.springboot.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.signoz.springboot.detect.AgentDetector;
import io.signoz.springboot.properties.SigNozProperties;
import io.signoz.springboot.properties.SigNozTracingProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * Spring {@code @Configuration} that bootstraps the OpenTelemetry SDK and
 * registers it as the global {@link OpenTelemetry} instance.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link SdkTracerProvider} — sends spans to SigNoz via OTLP gRPC</li>
 *   <li>{@link SdkMeterProvider} — sends metrics to SigNoz via OTLP gRPC</li>
 *   <li>Context propagation — W3C TraceContext (default), extendable via properties</li>
 * </ul>
 */
@Configuration
public class OpenTelemetrySdkConfig implements DisposableBean {

    private final SigNozProperties props;
    private OpenTelemetrySdk openTelemetrySdk;

    public OpenTelemetrySdkConfig(SigNozProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry openTelemetry() {
        // If the OTEL Java Agent is running, it already registered a global OpenTelemetry.
        // Use the agent's instance to avoid duplicate exporters.
        if (AgentDetector.isAgentPresent()) {
            try {
                return GlobalOpenTelemetry.get();
            } catch (Exception e) {
                return OpenTelemetry.noop();
            }
        }

        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), props.getServiceName(),
                        AttributeKey.stringKey("service.version"), props.getServiceVersion(),
                        AttributeKey.stringKey("deployment.environment"), props.getEnvironment()
                )));

        SigNozTracingProperties tracingProps = props.getTracing();
        Map<String, String> headers = props.getHeaders();

        // --- Tracer Provider ---
        io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder spanBuilder =
                OtlpGrpcSpanExporter.builder()
                        .setEndpoint(props.getEndpoint())
                        .setTimeout(Duration.ofMillis(tracingProps.getExportTimeoutMs()));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                spanBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        OtlpGrpcSpanExporter spanExporter = spanBuilder.build();

        Sampler sampler = tracingProps.getSampleRate() >= 1.0
                ? Sampler.alwaysOn()
                : Sampler.traceIdRatioBased(tracingProps.getSampleRate());

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setScheduleDelay(
                                Duration.ofMillis(tracingProps.getExportScheduleDelayMs()))
                        .build())
                .build();

        // --- Meter Provider ---
        io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder metricBuilder =
                OtlpGrpcMetricExporter.builder()
                        .setEndpoint(props.getEndpoint())
                        .setTimeout(Duration.ofMillis(tracingProps.getExportTimeoutMs()));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                metricBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        OtlpGrpcMetricExporter metricExporter = metricBuilder.build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(60))
                        .build())
                .build();

        // --- Propagation ---
        TextMapPropagator propagator = resolvePropagatior(tracingProps.getPropagation());

        this.openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setPropagators(ContextPropagators.create(propagator))
                .buildAndRegisterGlobal();

        return openTelemetrySdk;
    }

    /**
     * Resolves propagation format. B3 requires the optional
     * {@code opentelemetry-propagators-b3} dependency; falls back to W3C if absent.
     */
    private TextMapPropagator resolvePropagatior(SigNozTracingProperties.PropagationFormat format) {
        if (format == null) {
            return W3CTraceContextPropagator.getInstance();
        }
        switch (format) {
            case B3:
            case B3_MULTI:
                try {
                    Class<?> b3Class = Class.forName(
                            "io.opentelemetry.contrib.propagator.b3.B3Propagator");
                    String method = format == SigNozTracingProperties.PropagationFormat.B3
                            ? "injectingSingleHeader" : "injectingMultiHeaders";
                    return (TextMapPropagator) b3Class.getMethod(method).invoke(null);
                } catch (Exception ignored) {
                    // B3 extension not on classpath — fall back to W3C
                }
                return W3CTraceContextPropagator.getInstance();
            case W3C:
            default:
                return W3CTraceContextPropagator.getInstance();
        }
    }

    @Override
    public void destroy() {
        if (openTelemetrySdk != null) {
            openTelemetrySdk.getSdkTracerProvider().shutdown();
            openTelemetrySdk.getSdkMeterProvider().shutdown();
        }
    }
}
