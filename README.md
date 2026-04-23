# SigNoz Spring Boot Starter

> **[Full Documentation](https://rylxes.com/docs/signoz-spring-boot-starter)** — Complete usage guide, configuration reference, and API docs.

A zero-config, all-in-one Spring Boot starter that ships **logs**, **metrics**, and **traces** to
[SigNoz](https://signoz.io) via OpenTelemetry. Drop in one Maven dependency and get structured
logging, automatic field masking, trace-ID correlation, distributed spans, HTTP request logging,
audit trails, and JVM/HTTP metrics — with no code changes required.

[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)](https://www.oracle.com/java/)
[![Spring Boot 2.x](https://img.shields.io/badge/Spring%20Boot-2.x-green)](https://spring.io/projects/spring-boot)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-1.34-blueviolet)](https://opentelemetry.io)

---

## Features

| Feature | How it works |
|---------|-------------|
| **Structured logging** | JSON output via `SigNozJsonEncoder` — works out of the box, no `logback.xml` needed. Excludes Spring Boot's bootstrap `LoggerContext` properties (`CONSOLE_LOG_PATTERN`, `PID`, …) by default |
| **OTLP log export** | `OtlpLogbackAppender` ships logs directly to SigNoz collector |
| **Field masking** | `@Masked` on parameters; built-in rules for passwords, tokens, credit cards, SSNs |
| **Distributed tracing** | `@Traced` creates OpenTelemetry spans; trace-ID injected into MDC automatically |
| **HTTP logging** | `HttpLoggingFilter` logs method, path, status, duration for every request |
| **Agentless trace correlation** | `TraceIdMdcFilter` reuses inbound `traceparent` / `X-Request-ID` and emits both on the response — end-to-end log correlation across services without running an OTEL agent |
| **Audit trail** | `@AuditLog` publishes a structured `AuditEvent` (actor, action, outcome, args) |
| **JVM + HTTP metrics** | Micrometer OTLP registry sends metrics to SigNoz automatically |
| **Compile-time logger** | `@SigNozLog` injects `private static final Logger log` via APT (like `@Slf4j`) |
| **Method profiling** | `@Timed` logs method duration, WARN for slow methods, publishes Micrometer Timer |
| **Alert on failure** | `@AlertOnFailure` increments a Micrometer Counter when a method throws |
| **Outbound HTTP tracing** | Auto-injects `traceparent` + `X-Request-ID` into RestTemplate/WebClient calls (with MDC fallback when no OTEL span is active), logs outbound requests |
| **Kafka trace propagation** | Producer/Consumer interceptors propagate `traceId` via Kafka record headers |
| **Slow query detection** | DataSource proxy times SQL queries, WARN above threshold (default 500ms) |
| **Startup diagnostics** | Logs full config summary at boot — what's enabled, endpoints, thresholds |
| **User context enrichment** | Extracts `userId`/`userEmail`/`userRoles` from SecurityContext into MDC |
| **Error fingerprinting** | SHA-256 hash of exception type + stack → stable `errorId` in MDC for grouping |
| **Async context propagation** | `TracingTaskDecorator` copies MDC (traceId, requestId) to `@Async` worker threads |
| **Log sampling** | Probabilistic filtering — reduce INFO volume while ERROR/WARN always pass |

---

## Quick Start

### 1. Add the dependency

Pick the artifact that matches your Spring Boot version:

```xml
<!-- Spring Boot 2.x project -->
<dependency>
    <groupId>io.github.rylxes</groupId>
    <artifactId>signoz-spring-boot2-starter</artifactId>
    <version>1.0.3</version>
</dependency>
```

```xml
<!-- Spring Boot 3.x project (requires Java 17+) -->
<dependency>
    <groupId>io.github.rylxes</groupId>
    <artifactId>signoz-spring-boot3-starter</artifactId>
    <version>1.0.3</version>
</dependency>
```

### 2. Minimum configuration

```yaml
# application.yml
signoz:
  endpoint: http://localhost:4317   # SigNoz OTLP gRPC endpoint
  service-name: my-app
  service-version: 1.0.0
  environment: production
```

That's it — all features are on by default.

---

## OpenTelemetry Java Agent Support

The starter automatically detects the [OpenTelemetry Java Agent](https://opentelemetry.io/docs/zero-code/java/agent/)
(`opentelemetry-javaagent.jar`). When the agent is present, the starter **defers OTLP export
to the agent** and focuses on app-level features only.

| Component | Agent present | Agent absent |
|-----------|:---:|:---:|
| JSON logging / masking | Active | Active |
| HTTP request logging | Active | Active |
| Audit trail (`@AuditLog`) | Active | Active |
| `@Traced` spans | Uses agent's tracer | Uses starter's tracer |
| OTLP log export | Skipped (agent handles it) | Active |
| OTLP trace export | Skipped (agent handles it) | Active |
| OTLP metrics export | Skipped (agent handles it) | Active |
| Trace-ID in MDC (`traceId`, `spanId`) | From agent's active span | From inbound `traceparent`/`X-Request-ID`, else freshly minted |
| Cross-service trace propagation | Agent propagates W3C `traceparent` | Starter propagates `traceparent` + `X-Request-ID` via RestTemplate / WebClient interceptors |

**Recommended setup** — use the agent as the primary OTLP exporter, with the starter providing
app-level features:

```dockerfile
# Dockerfile
RUN wget https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
CMD java -javaagent:opentelemetry-javaagent.jar -jar my-app.jar
```

```yaml
# application.yml — no endpoint/headers needed, agent handles export
signoz:
  service-name: my-app
  logging:
    mask-enabled: true
```

Set the agent's export config via environment variables (e.g. in K8s):

```yaml
env:
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: "https://ingest.us.signoz.cloud:443"
  - name: OTEL_EXPORTER_OTLP_HEADERS
    value: "signoz-ingestion-key=<your-key>"
  - name: OTEL_SERVICE_NAME
    value: "my-app"
```

---

## SigNoz Cloud (without agent)

When running **without** the OTEL agent, the starter exports directly to SigNoz.
For [SigNoz Cloud](https://signoz.io/docs/cloud/), configure the ingestion endpoint
and authentication header:

```yaml
signoz:
  endpoint: https://ingest.us.signoz.cloud:443
  service-name: my-app
  service-version: 1.0.0
  environment: production
  headers:
    signoz-ingestion-key: <your-ingestion-key>
```

The `signoz.headers` map supports any custom headers. Each header is added to all
OTLP export requests (logs, traces, and metrics). Find your ingestion key in
**SigNoz Cloud > Settings > Ingestion**.

---

## Agentless Trace Propagation

You don't need the OpenTelemetry Java Agent to get **end-to-end log correlation**
across services. As long as the starter is on every hop, a single `traceId` /
`requestId` flows through the entire request path and lands in every log line —
making "find all logs for this incident" a single SigNoz query.

**How it works.** `TraceIdMdcFilter` chooses the request's identity in this
priority order:

1. **Active OTEL span** — when the agent (or SDK) is present, its
   `traceId`/`spanId`/`traceFlags` are used.
2. **Inbound W3C `traceparent` header** — validated (`00-<32hex>-<16hex>-<2hex>`),
   the inbound `traceId` is reused and a fresh 16-hex `spanId` is minted for this
   hop. Flags are preserved.
3. **Inbound `X-Request-ID` header** — agentless fallback for callers that don't
   speak W3C. Normalised to 32 hex characters for `traceparent` compatibility.
4. **Freshly minted IDs** — when we're the edge of the system.

The filter then writes **both** `traceparent` and `X-Request-ID` on the response,
and populates MDC with `traceId`, `spanId`, `traceFlags`, `requestId` — cleared in
`finally`.

**Outbound propagation.** `TracingRestTemplateInterceptor` and
`TracingWebClientFilter` inject `traceparent` + `X-Request-ID` on every outbound
call. When an OTEL span is active they read from the `SpanContext`; otherwise
they fall back to MDC values written by the filter. No agent required.

**What you get vs. what you don't.** This gives you log-level correlation:
filter by `traceId=abc…` in SigNoz and see every log across the whole
transaction. You do **not** get span waterfalls, timing per hop, or the SigNoz
trace view — those need actual spans, which means either the OTEL Java Agent,
the [OpenTelemetry Spring Boot Starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/),
or Spring Cloud Sleuth with the OTEL bridge. The filter is additive: when you
add the agent later, its trace IDs take priority and the MDC fallback becomes a
no-op.

> **Deployment tip:** end-to-end correlation only works when every service on
> the request path participates. Either install the starter on all of them, or
> make sure each service honours inbound `traceparent` and forwards it on
> outbound calls.

> **Reactive caveat:** in pure reactive `WebClient` chains, MDC is usually empty
> because Reactor doesn't copy thread-locals into its context. Servlet /
> blocking callers are unaffected. If you need full reactive correlation,
> install the OTEL agent or bridge MDC into `ContextView` yourself.

---

## Annotations Guide

### `@SigNozLog` — Compile-time logger injection

Annotate any class to have a `log` field injected at compile time. No more boilerplate `private static final Logger log = ...`.

```java
import io.signoz.springboot.annotation.SigNozLog;

@SigNozLog
@Service
public class PaymentService {
    // 'log' is available — injected by the annotation processor
    public void process(String orderId) {
        log.info("Processing payment for order {}", orderId);
    }
}
```

> **Note:** `@SigNozLog` uses the Java APT (annotation processing) mechanism. If your build system
> does not pass `--add-exports` flags on Java 9+, the processor emits a warning and skips injection —
> you can still declare `log` manually in that case.

---

### `@Masked` — Automatic field masking

Annotate method parameters to have their values replaced with `***` in log output.

```java
@Service
public class UserService {
    public void createUser(String username, @Masked String password) {
        // password is "***" in any log statement within this method's aspect
        log.info("Creating user: {}", username);
    }

    // Partial masking — shows first 0 and last 4 characters of a card number
    public void charge(@Masked(strategy = Masked.Strategy.PARTIAL) String cardNumber) {
        log.info("Charging card ending {}", cardNumber);  // e.g. "****1234"
    }
}
```

Built-in fields that are **always** masked (case-insensitive): `password`, `passwd`, `secret`,
`token`, `apikey`, `api_key`, `creditcard`, `cardnumber`, `cvv`, `ssn`, `authorization`,
`x-api-key`, `x-auth-token`.

Add your own fields in `application.yml`:

```yaml
signoz:
  logging:
    masked-fields:
      - myPrivateField
      - internalSecret
```

---

### `@Traced` — OpenTelemetry span creation

Wrap any Spring-managed method in an OTel span. Tags are key=value pairs.

```java
@Traced(operationName = "checkout", tags = {"domain=payment", "version=v2"})
public Order checkout(Cart cart) {
    // OTel span "checkout" is automatically created and closed
    return orderService.create(cart);
}
```

Class-level `@Traced` applies to all public methods:

```java
@Traced(operationName = "inventory")
@Service
public class InventoryService {
    public List<Item> findAvailable() { ... }  // traced as "inventory"
    public void restock(Item item) { ... }      // traced as "inventory"
}
```

---

### `@AuditLog` — Structured audit trail

Publishes an `AuditEvent` to the Spring `ApplicationEventPublisher` on every invocation. The actor is extracted from `SecurityContextHolder` (if Spring Security is present).

```java
@AuditLog(action = "USER_LOGIN", resourceType = "User", captureArgs = false)
public AuthResult login(String username, String password) {
    // AuditEvent published: action=USER_LOGIN, outcome=SUCCESS/FAILURE
    return authProvider.authenticate(username, password);
}

@AuditLog(action = "DELETE_ORDER", resourceType = "Order", resourceId = "#orderId")
public void deleteOrder(Long orderId) {
    // resourceId resolved from the 'orderId' parameter via SpEL
    orderRepository.deleteById(orderId);
}
```

The `SigNozAuditHandler` logs each event as a structured SLF4J entry on the `SIGNOZ_AUDIT` logger.
Listen to `AuditEvent` in your own `@EventListener` to add custom handling.

---

### `@Timed` — Method profiling

Measure any method's execution time. Logs duration at INFO, WARN for slow methods,
and optionally publishes a Micrometer Timer metric.

```java
@Timed("checkout.process")
public Order checkout(Cart cart) {
    // Logs: [SigNoz] Timed checkout.process completed in 142ms
    // If > 1000ms: [SigNoz] SLOW method checkout.process took 2341ms (threshold: 1000ms)
    return orderService.create(cart);
}
```

Configure the slow threshold:

```yaml
signoz:
  timed:
    slow-threshold-ms: 500   # default: 1000
```

---

### `@AlertOnFailure` — Failure metrics

Increment a Micrometer Counter every time a method throws an exception.
Use SigNoz dashboards to alert on counter spikes.

```java
@AlertOnFailure(metric = "payment.failures")
public PaymentResult charge(String cardToken, BigDecimal amount) {
    // On exception: counter "payment.failures" incremented with tags
    // class=PaymentService, method=charge, exception=PaymentDeclinedException
    return gateway.charge(cardToken, amount);
}
```

---

## Outbound HTTP Tracing

RestTemplate and WebClient calls are automatically traced. The starter injects a
W3C `traceparent` header and a matching `X-Request-ID`, and logs every outbound
request.

```
[SigNoz] Outbound GET https://api.example.com/users/123 -> 200 in 87ms
```

The identity comes from whichever of these is available:

1. The active OTEL `SpanContext` (agent or SDK path).
2. MDC values populated by [`TraceIdMdcFilter`](#agentless-trace-propagation) —
   so header propagation still works end-to-end in fully agentless deployments.

```yaml
signoz:
  outbound:
    enabled: true           # default
    log-requests: true      # log outbound calls
    propagate-headers: true # inject traceparent + X-Request-ID
```

---

## Kafka Trace Propagation

The starter provides Kafka interceptors that propagate `traceId` across message queues.

Add them to your Kafka producer/consumer config:

```yaml
spring:
  kafka:
    producer:
      properties:
        interceptor.classes: io.signoz.springboot.messaging.TracingProducerInterceptor
    consumer:
      properties:
        interceptor.classes: io.signoz.springboot.messaging.TracingConsumerInterceptor
```

The producer injects `traceId`, `spanId`, and `traceparent` into Kafka record headers.
The consumer extracts them into MDC so all downstream logs are correlated.

---

## Slow Query Detection

Automatically wraps your `DataSource` to time every SQL query. Queries exceeding
the threshold are logged at WARN with the SQL and duration.

```
[SigNoz] SLOW QUERY (1247ms > 500ms): SELECT * FROM transactions WHERE created_at > ?
```

```yaml
signoz:
  database:
    enabled: true
    slow-query-threshold-ms: 500  # default
    log-all-queries: false        # set true for development
    max-query-length: 1000        # truncate long SQL in logs
```

---

## Error Fingerprinting

When logging at ERROR level with a throwable, a stable `errorId` is added to MDC.
This is a SHA-256 hash of the exception type + top N stack frames, allowing you
to group identical errors in SigNoz dashboards.

```yaml
signoz:
  errors:
    enabled: true
    fingerprint-depth: 3  # number of stack frames to hash
```

---

## Async Context Propagation

`@Async` methods and `ExecutorService` tasks lose MDC context (traceId, requestId)
when switching threads. The starter provides `TracingTaskDecorator` that automatically
copies MDC to worker threads.

```yaml
signoz:
  async:
    enabled: true  # auto-configures TaskExecutorCustomizer
```

For raw `ExecutorService`, wrap it manually:

```java
ExecutorService traced = new TracingExecutorService(Executors.newFixedThreadPool(10));
```

---

## Structured JSON Logging

`SigNozJsonEncoder` (extends `LogstashEncoder`) produces one JSON object per log
event with `@timestamp`, `message`, `logger`, `thread`, `level`, MDC fields
(`traceId`, `spanId`, `requestId`, user context, error fingerprint, …), and any
structured arguments you log.

**Clean by default.** Spring Boot publishes internal bootstrap variables
(`CONSOLE_LOG_PATTERN`, `CONSOLE_LOG_CHARSET`, `FILE_LOG_PATTERN`,
`FILE_LOG_CHARSET`, `PID`, …) into the Logback `LoggerContext` so its pattern
layouts can resolve them. `LogstashEncoder` emits those as top-level JSON fields
by default — pure noise on every log line. The starter disables that behaviour
out of the box.

If you intentionally publish custom `LoggerContext` properties and want them on
every event, opt back in:

```yaml
signoz:
  logging:
    include-context: true   # default: false
```

---

## Log Sampling

Reduce log volume in high-traffic production environments. ERROR and WARN always
pass; other levels are sampled probabilistically.

```yaml
signoz:
  logging:
    sampling:
      enabled: true
      rate: 0.1                    # keep 10% of INFO/DEBUG logs
      always-log-levels:
        - ERROR
        - WARN
```

---

## User Context Enrichment

Automatically extracts user information from Spring Security's `SecurityContext`
and adds it to MDC. Every log line then includes `userId`, `userEmail`, `userRoles`.

```yaml
signoz:
  user-context:
    enabled: true
    extract-email: true
    extract-roles: true
```

> Requires Spring Security on the classpath. Uses reflection — no hard dependency.

---

## HTTP Logging

`HttpLoggingFilter` automatically logs every inbound HTTP request and response. Each log entry includes:

- HTTP method and URI
- Response status code
- Request duration (ms)
- Optionally: request/response headers and body

Sensitive headers (`Authorization`, `Cookie`, `X-API-Key`) are masked automatically.

**Exclude paths** from logging (default exclusions: `/actuator/**`, `/health/**`, `/favicon.ico`):

```yaml
signoz:
  web:
    exclude-paths:
      - /actuator/**
      - /internal/**
      - /metrics
```

**Disable request body logging** to reduce overhead on high-volume endpoints:

```yaml
signoz:
  web:
    log-request-body: false
    log-response-body: false
```

---

## Disabling Features

All features can be disabled independently:

| Property | Default | Effect when `false` |
|----------|---------|---------------------|
| `signoz.enabled` | `true` | Disables the entire starter |
| `signoz.tracing.enabled` | `true` | Disables span creation and OTel SDK init |
| `signoz.logging.mask-enabled` | `true` | Disables all field and message masking |
| `signoz.web.log-requests` | `true` | Disables HTTP request/response logging |
| `signoz.audit.enabled` | `true` | Disables `@AuditLog` aspect and handler |

Additional feature toggles:

| Property | Default | Effect when `false` |
|----------|---------|---------------------|
| `signoz.timed.enabled` | `true` | Disables `@Timed` profiling |
| `signoz.alerts.enabled` | `true` | Disables `@AlertOnFailure` counters |
| `signoz.outbound.enabled` | `true` | Disables outbound HTTP tracing |
| `signoz.messaging.enabled` | `true` | Disables Kafka trace propagation |
| `signoz.database.enabled` | `true` | Disables slow query detection |
| `signoz.user-context.enabled` | `true` | Disables user context enrichment |
| `signoz.errors.enabled` | `true` | Disables error fingerprinting |
| `signoz.async.enabled` | `true` | Disables async MDC propagation |
| `signoz.logging.sampling.enabled` | `false` | Enables log sampling (opt-in) |
| `signoz.logging.include-context` | `false` | When `true`, re-enables Logback `LoggerContext` properties as top-level JSON fields |

---

## Overriding Beans

Every auto-configured bean uses `@ConditionalOnMissingBean`, so you can override any component by
declaring your own `@Bean` of the same type.

**Custom masking registry** (add custom field strategies at startup):

```java
@Configuration
public class MyMaskingConfig {

    @Bean  // replaces the auto-configured MaskingRegistry
    public MaskingRegistry maskingRegistry(SigNozLoggingProperties props) {
        MaskingRegistry registry = new MaskingRegistry(props);
        registry.register("myCustomField", new FullMaskingStrategy());
        return registry;
    }
}
```

**Custom audit handler** (write to database instead of logs):

```java
@Configuration
public class MyAuditConfig {

    @Bean  // replaces SigNozAuditHandler
    public SigNozAuditHandler auditHandler(AuditRepository repo) {
        return event -> repo.save(AuditRecord.from(event));
    }
}
```

---

## Logback Integration

To include the SigNoz Logback configuration in your own `logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="logback-signoz.xml"/>

    <!-- Your additional appenders here -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="SIGNOZ_JSON"/>
    </root>
</configuration>
```

The bundled `logback-signoz.xml` activates the JSON encoder and OTLP appender based on the
`signoz.logging.mode` property (`OTLP` | `JSON` | `BOTH`). It sets
`<includeContext>false</includeContext>` on the encoder — see
[Structured JSON Logging](#structured-json-logging) to opt back in.

---

## Building the Project

```bash
# Build all 7 modules — requires JDK 17+ on the PATH (for the SB3 module)
mvn clean install

# Build only the Java 8 modules (skips SB3)
mvn clean install -pl signoz-annotations,signoz-annotation-processor,signoz-core,signoz-spring-boot2,signoz-spring-boot2-starter
```

> **Note:** `signoz-spring-boot3` compiles at Java 17 source level. The rest of the modules
> compile at Java 8 source level and run on any JDK 8+.

---

## Configuration Reference

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for the full property reference.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module dependency diagram, auto-configuration
load order, and AOP aspect ordering.
