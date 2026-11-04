# SigNoz Spring Boot Starter

[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)](https://www.oracle.com/java/)
[![Spring Boot 2.x](https://img.shields.io/badge/Spring%20Boot-2.x-green)](https://spring.io/projects/spring-boot)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-1.34-blueviolet)](https://opentelemetry.io)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey)](https://www.apache.org/licenses/LICENSE-2.0)

One dependency that sends your Spring Boot application's **logs, traces and metrics** to
[SigNoz](https://signoz.io) or Grafana over OpenTelemetry, with JSON logs, sensitive-field masking
and trace IDs in your logs. No code changes needed; every feature has a sensible default and
can be switched off.

Full documentation site: **[rylxes.com/docs/signoz-spring-boot-starter](https://rylxes.com/docs/signoz-spring-boot-starter)**

---

## Contents

- [Quick start](#quick-start)
- [What you get](#what-you-get)
- [Where telemetry goes](#where-telemetry-goes)
- [What's automatic and what needs wiring](#whats-automatic-and-what-needs-wiring)
- [Documentation](#documentation)
- [Contributing](#contributing)

---

## Quick start

**1. Add the starter** for your Spring Boot version:

| Spring Boot | Java | Artifact |
|---|---|---|
| 2.x | 8+ | `io.github.rylxes:signoz-spring-boot2-starter` |
| 3.x | 17+ | `io.github.rylxes:signoz-spring-boot3-starter` |

```xml
<dependency>
    <groupId>io.github.rylxes</groupId>
    <artifactId>signoz-spring-boot3-starter</artifactId>  <!-- or signoz-spring-boot2-starter -->
    <version>1.1.0</version>
</dependency>
```

**2. Point it at your collector** in `application.yml`:

```yaml
signoz:
  endpoint: http://localhost:4317   # SigNoz OTLP gRPC endpoint (this is the default)
  service-name: my-app
  service-version: 1.0.0
  environment: production
```

**3. Start the app.** At startup the starter logs a summary of what it enabled and where it
exports. Your console output is now JSON, log lines written while serving a request carry its
`requestId` and `trace_id`, and logs and traces are exported to SigNoz.

> **Metrics** need one more dependency, `io.micrometer:micrometer-registry-otlp`, plus
> `spring-boot-starter-actuator` for HTTP server metrics (the Spring Boot BOM manages both
> versions). See [Metrics and performance](docs/METRICS.md).

---

## What you get

**Logs** — [Logging guide](docs/LOGGING.md)
- JSON console output and OTLP log export, on Logback (Spring Boot's default) or Log4j2.
- Sensitive values masked everywhere they're logged: passwords, tokens, card numbers, SSNs,
  `Authorization` headers, query strings and request bodies. Add your own field names and patterns.
- `requestId`, `trace_id` and `span_id` on every line written during a request; `userId`,
  `userEmail` and `userRoles` for authenticated requests; a stable `errorId` on errors so identical failures group together.
- Optional sampling of INFO/DEBUG logs; ERROR and WARN always pass.

**Traces and correlation** — [Tracing guide](docs/TRACING.md)
- One trace ID follows a request across services over HTTP (RestTemplate, WebClient), Kafka, SQS,
  gRPC and WebSocket/STOMP, and into `@Async` threads.
- Works with or without the OpenTelemetry Java agent. Without it you get log correlation across
  services; with it you also get span waterfalls. The starter detects the agent and steps aside.
- `@Traced` creates spans around your own methods.

**HTTP** — [Web guide](docs/WEB.md)
- One access-log line per request (method, path, status, duration, optionally masked headers and
  bodies), for Spring MVC and Spring WebFlux.
- Outbound RestTemplate and WebClient calls logged with masked URLs.

**Metrics and performance** — [Metrics guide](docs/METRICS.md)
- JVM and HTTP metrics exported over OTLP through Micrometer.
- Slow SQL queries logged at WARN; `@Timed` for slow methods; `@AlertOnFailure` failure counters.

**Developer tools** — [Annotations guide](docs/ANNOTATIONS.md)
- `@SigNozLog` injects a logger at compile time, like Lombok's `@Slf4j`.
- `@AuditLog` publishes a structured audit event (actor, action, outcome) for each call.

---

## Where telemetry goes

The default is a self-hosted SigNoz collector on `localhost:4317`. Everything else is a few lines
of configuration, covered in [Exporting telemetry](docs/EXPORTING.md):

| Target | What to set |
|---|---|
| Self-hosted SigNoz | `signoz.endpoint` |
| [SigNoz Cloud](docs/EXPORTING.md#signoz-cloud) | `signoz.endpoint` + `signoz.headers.signoz-ingestion-key` |
| [OpenTelemetry Java agent](docs/EXPORTING.md#with-the-opentelemetry-java-agent) | Nothing: the agent exports and propagates; JSON logs, masking, HTTP logging, annotations and audit still come from the starter |
| [Grafana Cloud](docs/EXPORTING.md#grafana-cloud) | `signoz.exporter.backend: grafana-cloud` + Basic auth |
| [Self-hosted Loki / Tempo / Mimir](docs/EXPORTING.md#self-hosted-grafana-stack) | Per-signal endpoints under `signoz.exporter.*` |
| [Different backend per environment](docs/EXPORTING.md#different-backends-per-environment) | One Spring profile per backend |

SigNoz Cloud, for example:

```yaml
signoz:
  endpoint: https://ingest.us.signoz.cloud:443
  service-name: my-app
  headers:
    signoz-ingestion-key: ${SIGNOZ_INGESTION_KEY}
```

---

## What's automatic and what needs wiring

Most features need nothing beyond the dependency. A few integrations need a one-time line of setup,
because the starter can't attach itself to objects you build yourself:

| Feature | Setup |
|---|---|
| JSON logs, masking, OTLP export, HTTP access logs, trace IDs in the MDC, user context | None |
| Outbound RestTemplate / WebClient tracing | None, **if** you build clients from Spring Boot's `RestTemplateBuilder` or `WebClient.Builder` bean. A `new RestTemplate()` isn't traced. [Details](docs/TRACING.md#outbound-http) |
| `@Async` MDC propagation | None for Spring Boot's own executor. Executors you create need the `TracingTaskDecorator`. [Details](docs/TRACING.md#async-threads) |
| Kafka | Add the two interceptor classes to `spring.kafka.*.properties`. [Details](docs/TRACING.md#kafka) |
| SQS | Producer: add `TracingSqsRequestHandler` to your SQS client. Consumer: none. [Details](docs/TRACING.md#sqs) |
| gRPC | Add the client and server interceptors to your channel and server. [Details](docs/TRACING.md#grpc) |
| WebSocket / STOMP | Frames: none. Handshake: add `TracingWebSocketHandshakeInterceptor`. [Details](docs/TRACING.md#websocket--stomp) |
| Metrics | Add `micrometer-registry-otlp` (and Actuator for HTTP metrics). [Details](docs/METRICS.md#jvm-and-http-metrics) |
| `@SigNozLog` on Java 9+ | May need `--add-exports` compiler flags. [Details](docs/ANNOTATIONS.md#signozlog--compile-time-logger) |

To turn the whole starter off without removing it, set `signoz.enabled: false`. Every feature also
has its own switch; see [Customizing](docs/CUSTOMIZING.md).

---

## Documentation

| Guide | Read it to… |
|---|---|
| [Exporting telemetry](docs/EXPORTING.md) | Send data to SigNoz (self-hosted or Cloud), Grafana, or through the OpenTelemetry Java agent |
| [Logging](docs/LOGGING.md) | Understand the JSON output, MDC fields, masking rules, Log4j2 support, sampling and error IDs |
| [Tracing and correlation](docs/TRACING.md) | Follow a request across services and protocols, with or without the agent |
| [Web (Spring MVC and WebFlux)](docs/WEB.md) | Configure HTTP access logs, reactive apps and user context |
| [Metrics and performance](docs/METRICS.md) | Export metrics and find slow queries and methods |
| [Annotations](docs/ANNOTATIONS.md) | Use `@SigNozLog`, `@Masked`, `@Traced`, `@AuditLog`, `@Timed` and `@AlertOnFailure` |
| [Customizing](docs/CUSTOMIZING.md) | Switch features off and replace the starter's beans with your own |
| [Configuration reference](docs/CONFIGURATION.md) | Look up every `signoz.*` property and its default |
| [Architecture](docs/ARCHITECTURE.md) | See how the modules, auto-configurations and aspects fit together |
| [Changelog](CHANGELOG.md) | Check what changed between versions, and how to upgrade |

---

## Contributing

Contributions are welcome, from people and from AI coding agents.

- **People:** start with [CONTRIBUTING.md](CONTRIBUTING.md) for setup, build commands, project
  conventions and the pull request checklist.
- **AI agents:** read [AGENTS.md](AGENTS.md), a compact list of commands, rules and pitfalls for
  working in this repository. (Claude Code loads it through [CLAUDE.md](CLAUDE.md).)

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
