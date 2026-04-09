# Configuration Reference

All SigNoz Spring Boot Starter properties are nested under the `signoz` prefix and are configurable
in `application.yml` / `application.properties`. Every property has a sensible default so you only
need to specify what you want to change.

---

## Root properties (`signoz.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.enabled` | `boolean` | `true` | Master switch. Set to `false` to disable the entire starter without removing the dependency. |
| `signoz.endpoint` | `String` | `http://localhost:4317` | OTLP gRPC endpoint for the SigNoz collector (or SigNoz cloud ingest URL). |
| `signoz.service-name` | `String` | `application` | Logical service name shown in SigNoz traces, logs, and metrics. |
| `signoz.service-version` | `String` | `unknown` | Semantic version string attached to all telemetry (e.g. `1.2.3`). |
| `signoz.environment` | `String` | `default` | Deployment environment tag (e.g. `production`, `staging`, `dev`). |
| `signoz.headers` | `Map<String, String>` | `{}` | Custom headers sent with all OTLP export requests. Required for SigNoz Cloud authentication (e.g. `signoz-ingestion-key`). Ignored when the OpenTelemetry Java Agent is active. |

### SigNoz Cloud example

```yaml
signoz:
  endpoint: https://ingest.us.signoz.cloud:443
  service-name: my-app
  headers:
    signoz-ingestion-key: <your-ingestion-key>
```

### OpenTelemetry Java Agent

When `opentelemetry-javaagent.jar` is attached (via `-javaagent:`), the starter automatically
detects it and **skips its own OTLP export** for logs, traces, and metrics. The agent handles
all export — configure it via `OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_EXPORTER_OTLP_HEADERS`
environment variables. App-level features (masking, audit, HTTP logging, JSON formatting)
remain active.

---

## Logging properties (`signoz.logging.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.logging.mode` | `OTLP` \| `JSON` \| `BOTH` | `BOTH` | Output mode. `OTLP` sends logs to SigNoz via gRPC. `JSON` writes structured JSON to stdout. `BOTH` does both simultaneously. |
| `signoz.logging.mask-enabled` | `boolean` | `true` | Master switch for field and message masking. When `false`, all masking is bypassed. |
| `signoz.logging.masked-fields` | `List<String>` | see below | Case-insensitive field names whose values are fully replaced with `***`. |
| `signoz.logging.custom-patterns` | `List<PatternConfig>` | `[]` | Additional regex-based masking rules applied to log messages. |
| `signoz.logging.include-mdc` | `boolean` | `true` | Whether to include the MDC context map (e.g. `traceId`, `spanId`) in every log record. |
| `signoz.logging.include-caller-data` | `boolean` | `false` | Whether to include caller class and line number. Useful for debugging; has a small performance cost. |

**Default `masked-fields`** (always included unless you override the list):
`password`, `passwd`, `secret`, `token`, `apikey`, `api_key`, `creditcard`, `cardnumber`,
`card_number`, `cvv`, `ssn`, `authorization`, `x-api-key`, `x-auth-token`

**Built-in message-level patterns** (applied even without configuration):
- Passwords in log strings: `password=somevalue` → `password=***`
- Bearer tokens: `Bearer eyJ...` → `Bearer ***`
- Credit card numbers (16-digit groups): → `****-****-****-****`
- US SSNs (`XXX-XX-XXXX`): → `***-**-****`

### Custom pattern configuration

```yaml
signoz:
  logging:
    custom-patterns:
      - name: internalToken     # human-readable label (used in debug output)
        regex: "token=[A-Za-z0-9]+"   # Java regex; entire match → "***"
      - name: awsKey
        regex: "AKIA[A-Z0-9]{16}"
```

---

## Tracing properties (`signoz.tracing.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.tracing.enabled` | `boolean` | `true` | Enable/disable distributed tracing. When `false`, no OTel SDK is initialised and `@Traced` methods execute without spans. |
| `signoz.tracing.sample-rate` | `double` | `1.0` | Sampling probability: `1.0` = 100% (all requests), `0.1` = 10%. |
| `signoz.tracing.propagation` | `W3C` \| `B3` \| `B3_MULTI` | `W3C` | Context propagation format. Use `B3` for Zipkin-compatible systems. |
| `signoz.tracing.export-timeout-ms` | `long` | `5000` | Max milliseconds to wait for span export during graceful shutdown. |
| `signoz.tracing.export-schedule-delay-ms` | `long` | `1000` | Batch export delay in ms. Lower = lower latency; higher = better throughput. |

---

## Web / HTTP logging properties (`signoz.web.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.web.log-requests` | `boolean` | `true` | Whether to log all inbound HTTP requests. Set to `false` to disable `HttpLoggingFilter` entirely. |
| `signoz.web.log-request-body` | `boolean` | `true` | Capture and log the request body for POST/PUT/PATCH requests. |
| `signoz.web.log-response-body` | `boolean` | `false` | Capture and log the response body. Disable for endpoints returning large payloads. |
| `signoz.web.log-headers` | `boolean` | `false` | Include request headers in the log entry. Sensitive headers are masked automatically. |
| `signoz.web.log-query-string` | `boolean` | `true` | Include query string parameters in the logged URI. |
| `signoz.web.max-body-bytes` | `int` | `4096` | Maximum body size (in bytes) to include in the log entry. Larger bodies are truncated. |
| `signoz.web.exclude-paths` | `List<String>` | see below | Ant-style path patterns that are excluded from HTTP logging. |

**Default `exclude-paths`:** `/actuator/**`, `/health`, `/health/**`, `/favicon.ico`

---

## Audit log properties (`signoz.audit.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.audit.enabled` | `boolean` | `true` | Enable/disable the `@AuditLog` aspect. When `false`, no `AuditEvent` is published. |
| `signoz.audit.capture-args` | `boolean` | `true` | Global default for whether method arguments are included in audit entries. Per-method `@AuditLog(captureArgs=…)` takes precedence. |
| `signoz.audit.capture-result` | `boolean` | `false` | Global default for whether return values are included in audit entries. |
| `signoz.audit.include-thread` | `boolean` | `false` | Whether to include the current thread name in the audit entry. |

---

## Method profiling properties (`signoz.timed.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.timed.enabled` | `boolean` | `true` | Enable/disable `@Timed` method profiling. |
| `signoz.timed.slow-threshold-ms` | `long` | `1000` | Methods exceeding this duration (in ms) are logged at WARN. |

---

## Alert properties (`signoz.alerts.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.alerts.enabled` | `boolean` | `true` | Enable/disable `@AlertOnFailure` counter metrics. |

---

## Outbound HTTP properties (`signoz.outbound.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.outbound.enabled` | `boolean` | `true` | Enable/disable outbound HTTP tracing for RestTemplate/WebClient. |
| `signoz.outbound.log-requests` | `boolean` | `true` | Log outbound HTTP method, URL, status, and duration. |
| `signoz.outbound.propagate-headers` | `boolean` | `true` | Inject W3C `traceparent` header into outbound requests. |

---

## Messaging properties (`signoz.messaging.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.messaging.enabled` | `boolean` | `true` | Enable/disable Kafka trace propagation interceptors. |
| `signoz.messaging.propagate-trace` | `boolean` | `true` | Inject/extract `traceId` in Kafka record headers. |

---

## Database properties (`signoz.database.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.database.enabled` | `boolean` | `true` | Enable/disable slow query detection. |
| `signoz.database.slow-query-threshold-ms` | `long` | `500` | Queries exceeding this duration (ms) are logged at WARN. |
| `signoz.database.log-all-queries` | `boolean` | `false` | Log every query at INFO level (use in development only). |
| `signoz.database.max-query-length` | `int` | `1000` | Max SQL length in log output. Longer queries are truncated. |

---

## User context properties (`signoz.user-context.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.user-context.enabled` | `boolean` | `true` | Enable/disable user context enrichment from SecurityContext. |
| `signoz.user-context.extract-email` | `boolean` | `true` | Extract user email and add to MDC as `userEmail`. |
| `signoz.user-context.extract-roles` | `boolean` | `true` | Extract user roles and add to MDC as `userRoles`. |
| `signoz.user-context.principal-field` | `String` | `email` | Field name on the principal object to extract as `userId`. |

> Requires Spring Security on the classpath. Uses reflection — no hard dependency.

---

## Error fingerprinting properties (`signoz.errors.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.errors.enabled` | `boolean` | `true` | Enable/disable error fingerprinting. |
| `signoz.errors.fingerprint-depth` | `int` | `3` | Number of stack frames included in the fingerprint hash. |

---

## Async properties (`signoz.async.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.async.enabled` | `boolean` | `true` | Enable/disable automatic MDC propagation to `@Async` worker threads. |

---

## Log sampling properties (`signoz.logging.sampling.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signoz.logging.sampling.enabled` | `boolean` | `false` | Enable log sampling (opt-in). When disabled, all logs pass. |
| `signoz.logging.sampling.rate` | `double` | `1.0` | Sampling probability: `1.0` = all pass, `0.1` = 10% pass. |
| `signoz.logging.sampling.always-log-levels` | `List<String>` | `[ERROR, WARN]` | Log levels that always pass regardless of sampling rate. |

---

## Full `application.yml` example

```yaml
signoz:
  endpoint: http://signoz-collector:4317
  service-name: payment-service
  service-version: 2.1.0
  environment: production
  headers:
    signoz-ingestion-key: <your-key>  # for SigNoz Cloud

  logging:
    mode: BOTH
    mask-enabled: true
    masked-fields:
      - password
      - creditCard
      - ssn
      - authorization
      - myCustomField
    custom-patterns:
      - name: apiToken
        regex: "api_token=[A-Za-z0-9_-]+"
    include-mdc: true
    include-caller-data: false
    sampling:
      enabled: true
      rate: 0.1              # keep 10% of INFO/DEBUG logs
      always-log-levels:
        - ERROR
        - WARN

  tracing:
    enabled: true
    sample-rate: 0.1          # sample 10% of traces in production
    propagation: W3C
    export-timeout-ms: 5000
    export-schedule-delay-ms: 1000

  web:
    log-requests: true
    log-request-body: true
    log-response-body: false
    log-headers: false
    max-body-bytes: 4096
    exclude-paths:
      - /actuator/**
      - /internal/**
      - /health

  audit:
    enabled: true
    capture-args: true
    capture-result: false
    include-thread: false

  timed:
    enabled: true
    slow-threshold-ms: 500

  alerts:
    enabled: true

  outbound:
    enabled: true
    log-requests: true
    propagate-headers: true

  messaging:
    enabled: true
    propagate-trace: true

  database:
    enabled: true
    slow-query-threshold-ms: 500
    log-all-queries: false
    max-query-length: 1000

  user-context:
    enabled: true
    extract-email: true
    extract-roles: true

  errors:
    enabled: true
    fingerprint-depth: 3

  async:
    enabled: true
```
