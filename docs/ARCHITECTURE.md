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
  - **Logging** (`OtlpLogbackAppender`, `SigNozJsonEncoder`)
  - **Metrics** (`SigNozMetricsConfig`)
- Spring Boot classes are `optional` dependencies — the module can be used without Boot if needed

### `signoz-spring-boot2` / `signoz-spring-boot3`

- Contains only web-layer and auto-configuration classes
- `TraceIdMdcFilter` and `HttpLoggingFilter` (one copy per servlet namespace)
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
      ├── SigNozTracingAutoConfiguration   ← OpenTelemetrySdkConfig, SigNozTracer, TracedAspect
      ├── SigNozWebAutoConfiguration       ← TraceIdMdcFilter, HttpLoggingFilter, MaskedArgumentAspect
      │   (only when @ConditionalOnWebApplication(SERVLET))
      ├── SigNozAuditAutoConfiguration     ← AuditLogAspect, SigNozAuditHandler
      └── SigNozMetricsAutoConfiguration   ← OtlpMeterRegistry, MeterRegistryCustomizer
          (only when @ConditionalOnClass(MeterRegistry.class))
```

**Registration files:**

| Boot version | File |
|---|---|
| Spring Boot 2.x | `META-INF/spring.factories` (key: `EnableAutoConfiguration`) |
| Spring Boot 3.x | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |

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
