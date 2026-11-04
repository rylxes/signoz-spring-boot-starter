package io.signoz.springboot.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.registry.otlp.OtlpConfig;
import io.signoz.springboot.export.OtlpExporterSettings;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.signoz.springboot.properties.SigNozProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configures Micrometer to export metrics to SigNoz via the OTLP registry.
 *
 * <p>Registers:
 * <ul>
 *   <li>OTLP meter registry pointing at {@code signoz.endpoint}</li>
 *   <li>JVM metrics: memory, GC, threads, classes, CPU</li>
 *   <li>Process metrics: uptime</li>
 *   <li>Common tags: {@code service.name}, {@code environment}</li>
 * </ul>
 *
 * <p>Spring Boot's Actuator auto-configuration will also pick up the
 * {@code OtlpMeterRegistry} bean and add HTTP server metrics automatically.
 */
@Configuration
@ConditionalOnClass({MeterRegistry.class, OtlpMeterRegistry.class})
public class SigNozMetricsConfig {

    private final SigNozProperties props;

    public SigNozMetricsConfig(SigNozProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean(OtlpMeterRegistry.class)
    public OtlpMeterRegistry otlpMeterRegistry() {
        final OtlpExporterSettings exportSettings = OtlpExporterSettings.resolve(props);
        OtlpConfig config = new OtlpConfig() {
            @Override
            public String get(String key) {
                return null; // Use method overrides below
            }

            @Override
            public String url() {
                // Micrometer's registry speaks OTLP over HTTP only; see
                // OtlpExporterSettings.micrometerMetricsUrl() for how the URL is derived.
                return exportSettings.micrometerMetricsUrl();
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(60);
            }

            @Override
            public Map<String, String> resourceAttributes() {
                Map<String, String> attrs = new HashMap<String, String>();
                attrs.put("service.name", props.getServiceName());
                attrs.put("service.version", props.getServiceVersion());
                attrs.put("deployment.environment", props.getEnvironment());
                return attrs;
            }

            @Override
            public Map<String, String> headers() {
                return exportSettings.getHeaders();
            }
        };

        OtlpMeterRegistry registry = new OtlpMeterRegistry(config,
                io.micrometer.core.instrument.Clock.SYSTEM);

        // Bind JVM metrics
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        return registry;
    }
}
