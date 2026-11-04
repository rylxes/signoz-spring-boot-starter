package io.signoz.springboot.export;

import io.signoz.springboot.properties.SigNozExporterProperties;
import io.signoz.springboot.properties.SigNozProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.signoz.springboot.export.OtlpExporterSettings.Signal.LOGS;
import static io.signoz.springboot.export.OtlpExporterSettings.Signal.METRICS;
import static io.signoz.springboot.export.OtlpExporterSettings.Signal.TRACES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpExporterSettingsTest {

    /** Built at runtime so no credential-shaped literal sits in the source (secret scanners). */
    private static final String PASSWORD = "test-" + "only-value";

    @Test
    void defaultsAreUnchanged_grpcToTheConfiguredEndpoint() {
        SigNozProperties props = new SigNozProperties();
        props.getHeaders().put("signoz-ingestion-key", "k");

        OtlpExporterSettings s = OtlpExporterSettings.resolve(props);

        assertThat(s.getProtocol()).isEqualTo(OtlpExporterSettings.Protocol.GRPC);
        assertThat(s.endpoint(LOGS)).isEqualTo("http://localhost:4317");
        assertThat(s.endpoint(TRACES)).isEqualTo("http://localhost:4317");
        assertThat(s.endpoint(METRICS)).isEqualTo("http://localhost:4317");
        assertThat(s.getHeaders()).containsOnlyKeys("signoz-ingestion-key");
        // Micrometer keeps its previous derivation: HTTP port and /v1/metrics.
        assertThat(s.micrometerMetricsUrl()).isEqualTo("http://localhost:4318/v1/metrics");
    }

    @Test
    void grafanaCloudUsesHttpWithStandardPathsUnderTheGatewayAndBasicAuth() {
        SigNozProperties props = new SigNozProperties();
        props.setEndpoint("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/");
        props.getExporter().setBackend(SigNozExporterProperties.Backend.GRAFANA_CLOUD);
        props.getExporter().getBasicAuth().setUsername("123456");
        props.getExporter().getBasicAuth().setPassword(PASSWORD);

        OtlpExporterSettings s = OtlpExporterSettings.resolve(props);

        assertThat(s.getProtocol()).isEqualTo(OtlpExporterSettings.Protocol.HTTP_PROTOBUF);
        assertThat(s.endpoint(LOGS)).isEqualTo("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/logs");
        assertThat(s.endpoint(TRACES)).isEqualTo("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/traces");
        assertThat(s.endpoint(METRICS)).isEqualTo("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/metrics");
        assertThat(s.micrometerMetricsUrl()).isEqualTo(s.endpoint(METRICS));
        assertThat(s.getHeaders().get("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString(("123456:" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        assertThat(s.describe()).contains("backend=grafana-cloud", "protocol=http/protobuf", "auth=yes")
                .doesNotContain(PASSWORD);
    }

    @Test
    void perSignalEndpointsWinForASelfHostedGrafanaStack() {
        SigNozProperties props = new SigNozProperties();
        props.getExporter().setProtocol("http/protobuf");
        props.getExporter().setLogsEndpoint("http://loki:3100/otlp/v1/logs");
        props.getExporter().setTracesEndpoint("http://tempo:4318/v1/traces");

        OtlpExporterSettings s = OtlpExporterSettings.resolve(props);

        assertThat(s.endpoint(LOGS)).isEqualTo("http://loki:3100/otlp/v1/logs");
        assertThat(s.endpoint(TRACES)).isEqualTo("http://tempo:4318/v1/traces");
        // Unset: derived from the default endpoint, switched to the OTLP HTTP port.
        assertThat(s.endpoint(METRICS)).isEqualTo("http://localhost:4318/v1/metrics");
    }

    @Test
    void neverSendsTheSigNozIngestionKeyToAnotherBackend() {
        // Shared application.yml carries the SigNoz key; a dev profile switches to Grafana.
        SigNozProperties props = new SigNozProperties();
        props.getHeaders().put("Signoz-Ingestion-Key", "k");
        props.getHeaders().put("x-team", "orders");
        props.getExporter().setBackend(SigNozExporterProperties.Backend.GRAFANA_CLOUD);

        assertThat(OtlpExporterSettings.resolve(props).getHeaders())
                .doesNotContainKey("Signoz-Ingestion-Key").containsEntry("x-team", "orders");

        props.getExporter().setBackend(SigNozExporterProperties.Backend.SIGNOZ);
        assertThat(OtlpExporterSettings.resolve(props).getHeaders()).containsKey("Signoz-Ingestion-Key");
    }

    @Test
    void anExplicitAuthorizationHeaderIsNotOverridden() {
        SigNozProperties props = new SigNozProperties();
        props.getHeaders().put("authorization", "Bearer abc");
        props.getExporter().getBasicAuth().setUsername("u");

        assertThat(OtlpExporterSettings.resolve(props).getHeaders())
                .containsEntry("authorization", "Bearer abc").doesNotContainKey("Authorization");
    }

    @Test
    void parsesProtocolSpellingsAndRejectsUnknownOnes() {
        SigNozExporterProperties.Backend signoz = SigNozExporterProperties.Backend.SIGNOZ;
        assertThat(OtlpExporterSettings.parseProtocol("HTTP", signoz)).isEqualTo(OtlpExporterSettings.Protocol.HTTP_PROTOBUF);
        assertThat(OtlpExporterSettings.parseProtocol("http_protobuf", signoz)).isEqualTo(OtlpExporterSettings.Protocol.HTTP_PROTOBUF);
        assertThat(OtlpExporterSettings.parseProtocol(" gRPC ", signoz)).isEqualTo(OtlpExporterSettings.Protocol.GRPC);
        assertThat(OtlpExporterSettings.parseProtocol(null, SigNozExporterProperties.Backend.GRAFANA_CLOUD))
                .isEqualTo(OtlpExporterSettings.Protocol.HTTP_PROTOBUF);
        assertThatThrownBy(() -> OtlpExporterSettings.parseProtocol("http/json", signoz))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http/json");
    }

    @Test
    void anExplicitGrpcProtocolOverridesTheGrafanaDefault() {
        SigNozProperties props = new SigNozProperties();
        props.getExporter().setBackend(SigNozExporterProperties.Backend.GRAFANA_CLOUD);
        props.getExporter().setProtocol("grpc");
        props.setEndpoint("http://alloy:4317");

        OtlpExporterSettings s = OtlpExporterSettings.resolve(props);

        assertThat(s.getProtocol()).isEqualTo(OtlpExporterSettings.Protocol.GRPC);
        assertThat(s.endpoint(LOGS)).isEqualTo("http://alloy:4317");
    }
}
