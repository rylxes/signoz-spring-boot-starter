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
| **Structured logging** | JSON output via `SigNozJsonEncoder` (extends Logstash Encoder) |
| **OTLP log export** | `OtlpLogbackAppender` ships logs directly to SigNoz collector |
| **Field masking** | `@Masked` on parameters; built-in rules for passwords, tokens, credit cards, SSNs |
| **Distributed tracing** | `@Traced` creates OpenTelemetry spans; trace-ID injected into MDC automatically |
| **HTTP logging** | `HttpLoggingFilter` logs method, path, status, duration for every request |
| **Trace-ID correlation** | `TraceIdMdcFilter` injects `traceId`, `spanId`, `X-Request-ID` per request |
| **Audit trail** | `@AuditLog` publishes a structured `AuditEvent` (actor, action, outcome, args) |
| **JVM + HTTP metrics** | Micrometer OTLP registry sends metrics to SigNoz automatically |
| **Compile-time logger** | `@SigNozLog` injects `private static final Logger log` via APT (like `@Slf4j`) |

---

## Quick Start

### 1. Add the dependency

Pick the artifact that matches your Spring Boot version:

```xml
<!-- Spring Boot 2.x project -->
<dependency>
    <groupId>io.github.rylxes</groupId>
    <artifactId>signoz-spring-boot2-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```xml
<!-- Spring Boot 3.x project (requires Java 17+) -->
<dependency>
    <groupId>io.github.rylxes</groupId>
    <artifactId>signoz-spring-boot3-starter</artifactId>
    <version>1.0.0</version>
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
`signoz.logging.mode` property (`OTLP` | `JSON` | `BOTH`).

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
