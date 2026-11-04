# Exporting telemetry

Where the starter sends logs, traces and metrics, and how to point it at SigNoz, Grafana or the
OpenTelemetry Java agent. Every setting here is an ordinary Spring property; the full list is in
the [configuration reference](CONFIGURATION.md#exporter-properties-signozexporter).

- [Self-hosted SigNoz (default)](#self-hosted-signoz-default)
- [SigNoz Cloud](#signoz-cloud)
- [With the OpenTelemetry Java agent](#with-the-opentelemetry-java-agent)
- [Grafana Cloud](#grafana-cloud)
- [Self-hosted Grafana stack](#self-hosted-grafana-stack)
- [Different backends per environment](#different-backends-per-environment)
- [Checking what was configured](#checking-what-was-configured)

---

## Self-hosted SigNoz (default)

Out of the box the starter exports logs, traces and metrics over OTLP gRPC to
`http://localhost:4317`, the port a SigNoz collector listens on. Point it at yours:

```yaml
signoz:
  endpoint: http://signoz-otel-collector:4317
  service-name: my-app
  service-version: 1.0.0
  environment: production
```

`service-name`, `service-version` and `environment` are attached to every log record, span and
metric as the `service.name`, `service.version` and `deployment.environment` resource attributes.

---

## SigNoz Cloud

For [SigNoz Cloud](https://signoz.io/docs/cloud/), use your region's ingestion endpoint and send
your ingestion key as a header:

```yaml
signoz:
  endpoint: https://ingest.us.signoz.cloud:443
  service-name: my-app
  service-version: 1.0.0
  environment: production
  headers:
    signoz-ingestion-key: ${SIGNOZ_INGESTION_KEY}
```

Find the key in **SigNoz Cloud > Settings > Ingestion**. `signoz.headers` accepts any headers; each
one is added to every OTLP export request (logs, traces and metrics).

---

## With the OpenTelemetry Java agent

The starter detects the [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
(`opentelemetry-javaagent.jar`). When the agent is present, the starter **leaves export and
protocol instrumentation to the agent** and keeps its application-level features:

| Component | Agent present | Agent absent |
|-----------|:---:|:---:|
| JSON logging / masking | Active | Active |
| HTTP request logging | Active | Active |
| Audit trail (`@AuditLog`) | Active | Active |
| `@Traced` spans | Uses the agent's tracer | Uses the starter's tracer |
| OTLP log export | Skipped (agent exports) | Active |
| OTLP trace export | Skipped (agent exports) | Active |
| OTLP metrics export | Skipped (agent exports) | Active |
| Trace IDs in the MDC (`trace_id`, `span_id`, `trace_flags`) | From the agent's active span | From inbound `traceparent` / `X-Request-ID`, else new IDs |
| Propagation over HTTP | Agent propagates W3C `traceparent` | Starter's RestTemplate / WebClient interceptors |
| Propagation over Kafka, SQS, gRPC, WebSocket / STOMP | Agent propagates | Starter's interceptors (see [Tracing](TRACING.md)) |

**Recommended setup:** the agent exports, the starter adds JSON logs, masking and audit.

```dockerfile
# Dockerfile
RUN wget https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
CMD java -javaagent:opentelemetry-javaagent.jar -jar my-app.jar
```

```yaml
# application.yml: no endpoint or headers, the agent handles export
signoz:
  service-name: my-app
```

Configure the agent's export with environment variables, for example in Kubernetes:

```yaml
env:
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: "https://ingest.us.signoz.cloud:443"
  - name: OTEL_EXPORTER_OTLP_HEADERS
    value: "signoz-ingestion-key=<your-key>"
  - name: OTEL_SERVICE_NAME
    value: "my-app"
```

> **Masking and the agent's log export.** The agent's log appenders don't apply the starter's
> masking rules, so when the agent exports logs and masking rules are configured the starter logs
> a warning. To have the starter export logs (masked) instead, set
> `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED=false` or
> `OTEL_INSTRUMENTATION_LOG4J_APPENDER_ENABLED=false`.

---

## Grafana Cloud

The same starter exports to Grafana: logs to Loki, traces to Tempo and metrics to Mimir, all over
OTLP, which Grafana Cloud and Loki 3.x accept natively. Masking, JSON console output, trace
propagation and annotations work the same.

Take the OTLP endpoint, instance ID and an access token with write scopes from your stack's
OpenTelemetry (OTLP) connection details in the Grafana Cloud portal:

```yaml
signoz:
  endpoint: https://otlp-gateway-prod-eu-west-2.grafana.net/otlp
  service-name: my-app
  environment: production
  exporter:
    backend: grafana-cloud          # OTLP over HTTP; the gateway has no gRPC
    basic-auth:
      username: 123456              # instance ID
      password: ${GRAFANA_CLOUD_TOKEN}
```

Logs are sent to `…/otlp/v1/logs`, traces to `…/otlp/v1/traces` and metrics to
`…/otlp/v1/metrics`.

**Logs linked to traces.** Every exported log record carries its trace and span IDs (from the
active request, even without the agent), so Grafana's "Logs for this span" and Loki → Tempo links
work out of the box. By default Loki indexes `service.name` and `deployment.environment` as labels
and keeps the rest, including MDC fields (`mdc_*`), as structured metadata.

---

## Self-hosted Grafana stack

Point each signal at its backend, or send everything to Grafana Alloy or an OpenTelemetry
Collector:

```yaml
signoz:
  exporter:
    backend: otlp
    protocol: http/protobuf
    logs-endpoint: http://loki:3100/otlp/v1/logs
    traces-endpoint: http://tempo:4318/v1/traces
    metrics-endpoint: http://mimir:9009/otlp/v1/metrics
```

---

## Different backends per environment

One application can export to Grafana in one environment and SigNoz in another, for example dev →
Grafana Cloud and prod → SigNoz Cloud. Put each backend in its own Spring profile:

```yaml
# application.yml (shared)
signoz:
  service-name: orders
---
spring.config.activate.on-profile: prod
signoz:
  endpoint: https://ingest.eu.signoz.cloud:443
  headers:
    signoz-ingestion-key: ${SIGNOZ_INGESTION_KEY}
---
spring.config.activate.on-profile: dev
signoz:
  endpoint: https://otlp-gateway-prod-eu-west-2.grafana.net/otlp
  exporter:
    backend: grafana-cloud
    basic-auth:
      username: ${GRAFANA_INSTANCE_ID}
      password: ${GRAFANA_CLOUD_TOKEN}
```

Or set the same keys as environment variables per deployment (`SIGNOZ_ENDPOINT`,
`SIGNOZ_EXPORTER_BACKEND`, `SIGNOZ_EXPORTER_BASICAUTH_PASSWORD`, …).

Keep `signoz.headers` in the SigNoz profile: Spring merges map properties across profiles, so
headers in the shared file reach every environment. As a safeguard, the SigNoz-only
`signoz-ingestion-key` header is never sent when `signoz.exporter.backend` is not `signoz` (a
warning is logged once).

---

## Checking what was configured

The startup summary logs the resolved export settings, for example
`export: backend=grafana-cloud, protocol=http/protobuf, logs=…/otlp/v1/logs, … auth=yes`.
Credentials are never printed.
