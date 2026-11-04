# Metrics and performance

Exporting metrics, and the starter's tools for finding slow and failing code.

- [JVM and HTTP metrics](#jvm-and-http-metrics)
- [Slow query detection](#slow-query-detection)
- [Slow methods and failure counters](#slow-methods-and-failure-counters)

---

## JVM and HTTP metrics

Metrics are exported through Micrometer's OTLP registry, which the starter doesn't bring in
transitively. Add it, and Actuator if you want HTTP server metrics (Spring Boot's BOM manages both
versions):

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-otlp</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

With the registry on the classpath, the starter registers an `OtlpMeterRegistry` that sends to your
[export target](EXPORTING.md) with the same endpoint, headers and authentication as logs and traces.
It binds JVM metrics (memory, GC, threads, classes, CPU) and process uptime, and tags every metric
with your service name and environment. Actuator adds HTTP server metrics to the same registry.

It takes the place of Spring Boot's own OTLP registry, so `management.otlp.metrics.export.*` is not
used. To configure the registry yourself, declare an `OtlpMeterRegistry` bean; the starter then
backs off.

**Spring Boot 2:** Spring Boot 2.7 manages Micrometer 1.9, whose OTLP registry cannot send headers.
Metrics then go out without the SigNoz ingestion key or Grafana Cloud's Basic auth, and the starter
logs a warning at startup if any are configured. Send metrics through a local collector that adds
them, or use Micrometer 1.11 or later if your application can.

When the OpenTelemetry Java agent is present, the starter leaves metrics export to the agent.

---

## Slow query detection

When a `DataSource` is present, the starter wraps it and times every SQL statement. Statements over
the threshold are logged at WARN:

```
[SigNoz] SLOW QUERY (1247ms > 500ms): SELECT * FROM transactions WHERE created_at > ?
```

```yaml
signoz:
  database:
    enabled: true
    slow-query-threshold-ms: 500   # default
    log-all-queries: false         # true logs every statement (development only)
    max-query-length: 1000         # longer SQL is truncated in the log
```

---

## Slow methods and failure counters

Two annotations cover application code; both are described in [Annotations](ANNOTATIONS.md):

- [`@Timed`](ANNOTATIONS.md#timed--method-timing) logs each call's duration, logs slow calls at
  WARN and, with Micrometer present, records a Timer.
- [`@AlertOnFailure`](ANNOTATIONS.md#alertonfailure--failure-counters) increments a counter each
  time a method throws, for alerting on spikes.

Without Micrometer on the classpath both still log; only the metrics are skipped.
