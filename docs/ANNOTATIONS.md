# Annotations

The starter's annotations live in `io.signoz.springboot.annotation` and work on Spring-managed
beans (they're applied by aspects), except `@SigNozLog`, which works at compile time on any class.

| Annotation | Use it to… |
|---|---|
| [`@SigNozLog`](#signozlog--compile-time-logger) | Get a `log` field without declaring it |
| [`@Masked`](#masked--sensitive-parameters) | Keep a parameter's value out of audit entries |
| [`@Traced`](#traced--spans) | Wrap a method in an OpenTelemetry span |
| [`@AuditLog`](#auditlog--audit-trail) | Record who did what, and whether it worked |
| [`@Timed`](#timed--method-timing) | Log how long a method takes, and flag slow calls |
| [`@AlertOnFailure`](#alertonfailure--failure-counters) | Count a method's failures for alerting |

---

## `@SigNozLog` — Compile-time logger

Adds `private static final Logger log` to the class at compile time, like Lombok's `@Slf4j`.

```java
@SigNozLog
@Service
public class PaymentService {
    public void process(String orderId) {
        log.info("Processing payment for order {}", orderId);
    }
}
```

**On JDK 16 and later** the processor needs four `jdk.compiler` packages exported to it, and a
dependency can't add compiler flags to your build. Without them it prints "compiler internals
unavailable", skips the class, and the build fails wherever `log` is used. With Maven, fork the
compiler and pass the exports to its JVM:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <fork>true</fork>
        <compilerArgs>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

`<fork>` is required: the `-J` options configure the JVM running javac, which only a forked compiler
has. With other build tools, pass the same four `--add-exports` options to that JVM. If you can't,
declare the logger yourself (`private static final Logger log = LoggerFactory.getLogger(...)`).

---

## `@Masked` — Sensitive parameters

Marks a parameter as sensitive. Its value is masked wherever the starter captures method arguments,
which today means the arguments recorded by [`@AuditLog`](#auditlog--audit-trail). It never changes
the value your method receives.

```java
@AuditLog(action = "USER_CREATE", resourceType = "User")
public void createUser(String username, @Masked String password) {
    // The audit entry records password as ***; the method gets the real value.
}

@AuditLog(action = "CARD_CHARGE", resourceType = "Payment")
public void charge(@Masked(strategy = Masked.MaskingStrategy.PARTIAL) String cardNumber) {
    // The audit entry keeps only the start and end of the card number.
}
```

`@Masked` doesn't affect values you pass to a logger yourself. Those are masked by field name and
message pattern; see [Masking sensitive values](LOGGING.md#masking-sensitive-values).

---

## `@Traced` — Spans

Wraps a method in an OpenTelemetry span. Tags are `key=value` pairs added as span attributes.

```java
@Traced(operationName = "checkout", tags = {"domain=payment", "version=v2"})
public Order checkout(Cart cart) {
    return orderService.create(cart);
}
```

On a class, it applies to every public method:

```java
@Traced(operationName = "inventory")
@Service
public class InventoryService {
    public List<Item> findAvailable() { ... }  // span "inventory"
    public void restock(Item item) { ... }      // span "inventory"
}
```

With the OpenTelemetry Java agent present, spans are created with the agent's tracer and appear in
the agent's traces. Switch off with `signoz.tracing.enabled: false`.

---

## `@AuditLog` — Audit trail

Publishes an `AuditEvent` through Spring's `ApplicationEventPublisher` on every call, with the
action, resource, outcome (`SUCCESS` or `FAILURE`) and actor. The actor comes from Spring
Security's `SecurityContextHolder` when it's present.

```java
@AuditLog(action = "USER_LOGIN", resourceType = "User", captureArgs = false)
public AuthResult login(String username, String password) {
    return authProvider.authenticate(username, password);
}

@AuditLog(action = "DELETE_ORDER", resourceType = "Order", resourceId = "#orderId")
public void deleteOrder(Long orderId) {
    // resourceId is resolved from the orderId parameter with SpEL
    orderRepository.deleteById(orderId);
}
```

The default `SigNozAuditHandler` writes each event as a structured entry on the `SIGNOZ_AUDIT`
logger. To store events elsewhere, add an `@EventListener` for `AuditEvent` or
[replace the handler](CUSTOMIZING.md#custom-audit-handler). Argument and result capture defaults
are under `signoz.audit.*`.

---

## `@Timed` — Method timing

Logs each call's duration at INFO and slow calls at WARN. With Micrometer on the classpath it also
records a Timer with the given name.

```java
@Timed("checkout.process")
public Order checkout(Cart cart) {
    // [SigNoz] Timed checkout.process completed in 142ms
    // over the threshold: [SigNoz] SLOW method checkout.process took 2341ms (threshold: 1000ms)
    return orderService.create(cart);
}
```

```yaml
signoz:
  timed:
    slow-threshold-ms: 500   # default: 1000
```

---

## `@AlertOnFailure` — Failure counters

Increments a Micrometer counter each time the method throws, tagged with the class, method and
exception type. Alert on spikes in SigNoz. Without Micrometer the failure is only logged.

```java
@AlertOnFailure(metric = "payment.failures")
public PaymentResult charge(String cardToken, BigDecimal amount) {
    // On exception: payment.failures +1, tags class=PaymentService, method=charge,
    // exception=PaymentDeclinedException
    return gateway.charge(cardToken, amount);
}
```
