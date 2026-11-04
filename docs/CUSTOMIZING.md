# Customizing

Turning features off, and replacing the starter's beans with your own.

- [Turning features off](#turning-features-off)
- [Replacing beans](#replacing-beans)
  - [Custom masking registry](#custom-masking-registry)
  - [Custom audit handler](#custom-audit-handler)

---

## Turning features off

`signoz.enabled: false` turns off the whole starter without removing the dependency. Each feature
also has its own switch:

| Property | Default | Controls |
|----------|---------|----------|
| `signoz.enabled` | `true` | The entire starter |
| `signoz.tracing.enabled` | `true` | OpenTelemetry SDK setup and `@Traced` spans |
| `signoz.logging.mask-enabled` | `true` | All field and message masking |
| `signoz.logging.sampling.enabled` | `false` | Log sampling (opt-in) |
| `signoz.web.log-requests` | `true` | HTTP access logging |
| `signoz.user-context.enabled` | `true` | User fields in the MDC |
| `signoz.outbound.enabled` | `true` | RestTemplate / WebClient tracing and logging |
| `signoz.messaging.enabled` | `true` | Kafka propagation |
| `signoz.sqs.enabled` | `true` | SQS propagation |
| `signoz.grpc.enabled` | `true` | gRPC propagation |
| `signoz.websocket.enabled` | `true` | WebSocket / STOMP propagation |
| `signoz.async.enabled` | `true` | MDC propagation to `@Async` threads |
| `signoz.audit.enabled` | `true` | `@AuditLog` |
| `signoz.timed.enabled` | `true` | `@Timed` |
| `signoz.alerts.enabled` | `true` | `@AlertOnFailure` |
| `signoz.database.enabled` | `true` | Slow query detection |
| `signoz.errors.enabled` | `true` | Error fingerprinting (`errorId`) |

Every property, with its finer-grained options, is in the
[configuration reference](CONFIGURATION.md).

---

## Replacing beans

Every auto-configured bean is declared with `@ConditionalOnMissingBean`, so declaring your own
`@Bean` of the same type replaces the starter's.

### Custom masking registry

Add field strategies in code, at startup:

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

Most needs are covered by configuration alone: see `masked-fields`, `field-strategies`, `profiles`
and `custom-patterns` in [Masking sensitive values](LOGGING.md#masking-sensitive-values).

### Custom audit handler

The default `SigNozAuditHandler` writes audit events to the `SIGNOZ_AUDIT` logger. To write them
somewhere else instead, subclass it:

```java
@Component
public class DatabaseAuditHandler extends SigNozAuditHandler {

    private final AuditRepository repository;

    public DatabaseAuditHandler(AuditRepository repository) {
        this.repository = repository;
    }

    @Override
    @EventListener
    public void onAuditEvent(AuditEvent event) {
        repository.save(AuditRecord.from(event));
    }
}
```

To keep the log entries *and* store events, leave the default handler in place and add your own
`@EventListener` for `AuditEvent`. Listeners run synchronously in the caller's thread; annotate
yours with `@Async` to handle events in the background.
