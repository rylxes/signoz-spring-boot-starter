# Architecture

This document is for contributors and integrators who want to understand how the SigNoz Spring Boot
Starter is structured, how its modules relate to each other, and where to look when extending it.

---

## Module Dependency Diagram

```
signoz-spring-boot2-starter (pom only)
  ├── signoz-annotations          (Java 8, zero runtime deps)
  ├── signoz-annotation-processor (Java 8, APT — provided/optional)
  ├── signoz-core                 (Java 8, Spring Framework, OTel)
  └── signoz-spring-boot2         (Java 8, Spring Boot 2.x, javax.servlet)

signoz-spring-boot3-starter (pom only)
  ├── signoz-annotations          (same jar as above)
  ├── signoz-annotation-processor (same jar as above)
  ├── signoz-core                 (same jar as above)
  └── signoz-spring-boot3         (Java 17, Spring Boot 3.x, jakarta.servlet)
```

**Key constraint:** `signoz-spring-boot2` and `signoz-spring-boot3` are **binary-incompatible** — one
uses `javax.servlet.*`, the other `jakarta.servlet.*`. This is why the web layer is duplicated into
two separate modules rather than sharing a single compiled artifact.

---

## Module Responsibilities

### `signoz-annotations`

- Zero runtime dependencies
- Contains only annotation types: `@SigNozLog`, `@Masked`, `@Traced`, `@AuditLog`
- Java source/target 8
- Consumed by user application code

### `signoz-annotation-processor`

- APT processor for `@SigNozLog`
- At compile time, uses `com.sun.tools.javac.*` to inject `private static final Logger log` into
  annotated classes — equivalent to Lombok's `@Slf4j` but without Lombok
- Graceful fallback: if internal compiler APIs are unavailable (e.g. strict module system without
  `--add-exports`), the processor emits a `WARNING` and skips injection

### `signoz-core`

- No servlet dependency — compatible with Spring MVC, Spring WebFlux, or plain Spring
- Contains:
  - **Properties** (`SigNozProperties`, `SigNozLoggingProperties`, etc.)
  - **Masking** (`MaskingRegistry`, `MaskingStrategy` hierarchy, `MaskedArgumentAspect`)
  - **Tracing** (`OpenTelemetrySdkConfig`, `SigNozTracer`, `TracedAspect`)
  - **Audit** (`AuditLogAspect`, `SigNozAuditHandler`, `AuditEvent`)
  - **Logging** (`OtlpLogbackAppender`, `SigNozJsonEncoder`; shared `OtlpLogSupport`; and in
    `logging.log4j2`: `SigNozLog4j2JsonLayout`, `OtlpLog4j2Appender`, `SamplingLog4j2Filter`,
    `SigNozLog4j2Configurer`, compiled against Log4j2 2.17.2)
  - **Metrics** (`SigNozMetricsConfig`)
- Spring Boot classes are `optional` dependencies — the module can be used without Boot if needed

### `signoz-spring-boot2` / `signoz-spring-boot3`

- Contains only web-layer and auto-configuration classes
- `TraceIdMdcFilter` and `HttpLoggingFilter` (one copy per servlet namespace)
- `SigNozReactiveWebAutoConfiguration`, which registers the reactive filters from
  `signoz-core`'s `io.signoz.springboot.webflux` package (one shared copy: the
  WebFlux API has no `javax`/`jakarta` split). The core classes are compiled
  against Spring 5.3 / Reactor 3.4 and use only APIs that also exist in Spring 6
  (`getRawStatusCode()`, `DataBuffer.getByte()`); both modules' integration tests
  run them against their own Spring version.
- Spring Boot 3 only: `SigNozMdcThreadLocalAccessor`, which lets Reactor's
  automatic context propagation restore the SigNoz MDC on every thread hop
- All auto-configuration entry points
- Registration files for Spring Boot's auto-configuration loader

---

## Auto-Configuration Load Order

When Spring Boot starts, it reads the registration file and loads `SigNozAutoConfiguration`:

```
SigNozAutoConfiguration                 ← entry point (@AutoConfiguration / @Configuration)
  │
  ├── @EnableConfigurationProperties
  │   ├── SigNozProperties
  │   ├── SigNozLoggingProperties
  │   ├── SigNozTracingProperties
  │   ├── SigNozWebProperties
  │   └── SigNozAuditProperties
  │
  └── @Import (in order)
      ├── SigNozLoggingAutoConfiguration   ← MaskingRegistry, SigNozJsonEncoder, logback configurer
      ├── SigNozLog4j2AutoConfiguration    ← SigNozLog4j2Configurer
      │   (only when the Log4j2 API is bound to log4j-core, i.e. Log4j2 is the backend)
      ├── SigNozTracingAutoConfiguration   ← OpenTelemetrySdkConfig, SigNozTracer, TracedAspect
      ├── SigNozWebAutoConfiguration       ← TraceIdMdcFilter, HttpLoggingFilter, MaskedArgumentAspect
      │   (only when @ConditionalOnWebApplication(SERVLET))
      ├── SigNozAuditAutoConfiguration     ← AuditLogAspect, SigNozAuditHandler
      ├── SigNozMessagingAutoConfiguration ← Tracing{Producer,Consumer}Interceptor (Kafka)
      ├── SigNozSqsAutoConfiguration       ← TracingSqsRequestHandler + @SqsListener aspect
      │   (only when agent absent + AWS SDK v1 on classpath)
      ├── SigNozGrpcAutoConfiguration      ← Tracing{Client,Server}Interceptor (gRPC)
      │   (only when agent absent + io.grpc on classpath)
      ├── SigNozWebSocketAutoConfiguration ← STOMP ChannelInterceptor + handshake
      │   (only when agent absent + spring-websocket on classpath)
      └── SigNozMetricsAutoConfiguration   ← OtlpMeterRegistry, MeterRegistryCustomizer
          (only when @ConditionalOnClass(MeterRegistry.class))
```

**Registration files:**

| Boot version | File |
|---|---|
| Spring Boot 2.x | `META-INF/spring.factories` (key: `EnableAutoConfiguration`) |
| Spring Boot 3.x | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |

---

## Cross-service trace propagation

Each protocol module owns a piece of the same goal: keep the W3C `traceparent`
on the wire and the trace IDs in MDC across service hops, regardless of how
the services talk to each other.

### Module / wire-format matrix

| Protocol | Producer / outbound | Consumer / inbound | Wire artefact |
|---|---|---|---|
| HTTP | `TracingRestTemplateInterceptor` / `TracingWebClientFilter` | `TraceIdMdcFilter` (servlet) / `TraceIdWebFilter` (WebFlux) | `traceparent` HTTP header |
| Kafka | `TracingProducerInterceptor` | `TracingConsumerInterceptor` | record headers (`traceparent`; legacy `traceId`, `spanId` for older consumers) |
| SQS | `TracingSqsRequestHandler` (AWS SDK v1) | `@SqsListener` aspect (awspring v2/v3 / legacy) | SQS `MessageAttributes` (`traceparent`) |
| gRPC | `TracingGrpcClientInterceptor` | `TracingGrpcServerInterceptor` | gRPC `Metadata` (`traceparent`) |
| WebSocket / STOMP | `TracingStompChannelInterceptor` (OUTBOUND) + handshake interceptor | `TracingStompChannelInterceptor` (INBOUND) + handshake interceptor | STOMP native header + WebSocket session attribute |

All wire formats use the same canonical W3C `traceparent` value, produced via
`TraceContextCodec.currentTraceparent()` and parsed via
`TraceContextCodec.parse(...)`. End-to-end correctness across these protocols
is asserted by `EndToEndTracePropagationTest` in `signoz-core`.

### Agent-detection gating

The three new modules (SQS, gRPC, WebSocket) are gated by **three** stacked
conditions on their auto-configuration:

```java
@Conditional(OnMissingAgentCondition.class)        // skip if agent active
@ConditionalOnClass(name = "<protocol-API class>")  // skip if user doesn't use it
@ConditionalOnProperty("signoz.<x>.enabled", matchIfMissing = true)  // user kill switch
```

`OnMissingAgentCondition` returns `!AgentDetector.isAgentPresent()`.
`AgentDetector` caches its result for the JVM lifetime and uses three signals
in order: the `otel.javaagent.version` system property, the
`io.opentelemetry.javaagent.OpenTelemetryAgent` class on the classpath, and a
non-noop `GlobalOpenTelemetry` instance (compared by reference equality with
the noop singleton — *not* by class-name substring, which produces false
positives once anything has touched `GlobalOpenTelemetry`).

This gating is the contract behind the README's "Agent-aware" callouts: when
the agent is on, the agent owns instrumentation; when it's off, the starter
fills the gap. There is no double-instrumentation path.

### MDC scope caveats

- **Synchronous executors** (gRPC `DirectExecutor`, default Kafka consumer
  loop, awspring v2 `SimpleMessageListenerContainer`): MDC populated by an
  inbound interceptor is visible inside the user handler.
- **Thread-pool executors** (gRPC custom executor, async listeners): the
  handler may run on a different thread than the one that ran the interceptor.
  Wire-side propagation always works; MDC inside the handler is best-effort.
  This is consistent across every MDC-based correlation pattern in this
  starter (Kafka, SQS, gRPC, STOMP) — full cross-thread MDC is the OTel
  agent's job.
- **WebFlux**: the reactive filters keep the request's MDC entries in the
  Reactor context (`ReactiveMdc.CONTEXT_KEY`), which follows the request across
  threads. `TracingWebClientFilter` reads them from there, so wire-side
  propagation is exact. Thread MDC is set while the filter chain is subscribed;
  on Spring Boot 3 Reactor's automatic context propagation restores it on every
  hop, on Spring Boot 2 handlers use `ReactiveMdc.onNext(...)` past the first hop.
- **MDC key names**: every inbound path writes the OTel-native `trace_id` /
  `span_id` / `trace_flags` (the same keys the OTel agent uses): both HTTP filters
  via `TraceIdentity`, and the Kafka, SQS, gRPC and STOMP consumers via
  `TraceContextCodec.putMdc(...)`. `TraceContextCodec.mdcTraceparent()` reads the
  snake_case family first and falls back to the legacy camelCase keys (which
  application code may still write), never mixing the two, and only emits a
  `traceparent` whose values are valid hex.

---

## AOP Aspect Ordering

Multiple aspects can intercept the same method call. The `@Order` annotations control which aspect
runs outermost (lowest number = outermost = runs first on entry, last on exit):

| Aspect | `@Order` | Runs... |
|--------|----------|---------|
| `MaskedArgumentAspect` | `1` | First — masks args before they reach any logger |
| `TracedAspect` | `10` | Second — creates the OTel span around the method |
| `AuditLogAspect` | `20` | Third — inside the span, so `traceId` is available |

This ordering ensures that:
1. Sensitive values are masked before any logging occurs inside the business method
2. Audit events are always enriched with the correct `traceId` from the surrounding span

---

## OTel SDK Singleton Hazard

`OpenTelemetrySdkConfig` calls `GlobalOpenTelemetry.set(sdk)` at startup. This is a **JVM-global
singleton** — it can only be set once. Attempting to register it a second time (e.g. in tests that
start multiple Spring contexts) throws an `IllegalStateException`.

**How the starter handles this:** `OpenTelemetrySdkConfig` is guarded by
`@ConditionalOnMissingBean(OpenTelemetry.class)`. If any other bean of type `OpenTelemetry` is
already in the context (including one provided by a test), the SDK config class is skipped entirely.

**How tests handle this:**

1. `ApplicationContextRunner`-based tests supply `OpenTelemetry.noop()` via `.withBean(...)` so the
   SDK is never initialised:
   ```java
   new ApplicationContextRunner()
       .withConfiguration(AutoConfigurations.of(SigNozAutoConfiguration.class))
       .withBean("openTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
       .withPropertyValues("signoz.tracing.enabled=false");
   ```

2. `@SpringBootTest` tests use `TestSigNozApplication`, which declares a `@Bean openTelemetry()`
   returning `OpenTelemetry.noop()`. Combined with `signoz.tracing.enabled=false` in
   `application-test.yml`, the SDK config class is reliably skipped.

---

## Masking Architecture

```
MaskingRegistry (Spring @Component)
  │
  ├── fieldStrategies: Map<String, MaskingStrategy>
  │   ├── Built-in: password, ssn, apikey, creditcard, ... → FullMaskingStrategy
  │   ├── Built-in: creditcard, pan → PartialMaskingStrategy(0, 4, '*')
  │   └── User-configured: signoz.logging.masked-fields → FullMaskingStrategy
  │
  └── messagePatterns: List<RegexMaskingStrategy>
      ├── Built-in: password=<value> → password=***
      ├── Built-in: Bearer <token> → Bearer ***
      ├── Built-in: 16-digit card numbers → ****-****-****-****
      ├── Built-in: SSN (XXX-XX-XXXX) → ***-**-****
      └── User-configured: signoz.logging.custom-patterns
```

`MaskingRegistry.mask(fieldName, value)` — field-based lookup (used by `SigNozJsonEncoder`)
`MaskingRegistry.maskMessage(text)` — regex scan over free-form log messages
`MaskingRegistry.maskJsonString(json)` — field-based + pattern scan over a JSON string

`SigNozJsonEncoder` does not run these regexes over the JSON part of its serialised output line. Its
second pass walks the JSON object with a streaming parser and masks each value unescaped: values under
a sensitive field name (including list elements and nested objects) via `mask`, every other string via
`maskJsonString`. A configured prefix or suffix is masked as free text around the object. Only if no
JSON object can be found does it fall back to `maskJsonString` over the raw line. A free-text pattern
such as `password=\S+` run over the JSON would otherwise pass the closing quote of the `message`
value, because LogstashEncoder writes no whitespace between fields, and swallow the rest of the line.

---

## Adding a New Masking Strategy

1. Implement `MaskingStrategy` in `signoz-core`:
   ```java
   public class PhoneMaskingStrategy implements MaskingStrategy {
       @Override
       public String mask(String fieldName, String rawValue) {
           if (rawValue == null) return "***";
           // keep last 4 digits only
           return rawValue.length() > 4
               ? "***-" + rawValue.substring(rawValue.length() - 4)
               : "***";
       }
   }
   ```

2. Register it at runtime via a custom `MaskingRegistry` bean (see README — Overriding Beans).

---

## Adding a New Auto-Configuration

1. Create a `@Configuration` class in `signoz-spring-boot2` (and mirror it in `signoz-spring-boot3`)
2. Add it to the `@Import` list in `SigNozAutoConfiguration`
3. Guard it with appropriate `@ConditionalOn*` annotations
4. Add a corresponding `@ConditionalOnMissingBean` on each `@Bean` so users can override it

---

## Extending Audit Events

`AuditEvent` is a plain Spring `ApplicationEvent`. You can listen to it anywhere in your application:

```java
@Component
public class DatabaseAuditListener {

    @EventListener
    public void onAuditEvent(AuditEvent event) {
        auditRepository.save(new AuditRecord(
            event.getAction(),
            event.getActor(),
            event.getOutcome().name(),
            event.getTimestamp()
        ));
    }
}
```

The default `SigNozAuditHandler` writes to SLF4J with a marker. Both run in the same transaction
context as the caller — to handle events asynchronously, annotate your listener with `@Async`.
