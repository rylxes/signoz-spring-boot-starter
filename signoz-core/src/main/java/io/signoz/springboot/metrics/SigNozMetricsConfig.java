package io.signoz.springboot.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.registry.otlp.OtlpConfig;
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
        OtlpConfig config = new OtlpConfig() {
            @Override
            public String get(String key) {
                return null; // Use method overrides below
            }

            @Override
            public String url() {
                // Micrometer OTLP uses HTTP/protobuf endpoint (port 4318 for OTLP HTTP)
                String endpoint = props.getEndpoint();
                // Convert gRPC endpoint (4317) hint to HTTP endpoint (4318) if needed
                if (endpoint.contains(":4317")) {
                    endpoint = endpoint.replace(":4317", ":4318");
                }
                return endpoint + "/v1/metrics";
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
                Map<String, String> h = props.getHeaders();
                return (h != null) ? h : new HashMap<String, String>();
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
