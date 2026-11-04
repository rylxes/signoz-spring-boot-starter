# End-to-end tests

Two copies of a small demo service, **A** and **B**, run as separate JVMs and call each other over
HTTP, Kafka, SQS, gRPC and WebSocket/STOMP. They report to an in-process OTLP receiver listening on
the collector's standard ports. The tests check each feature of the starter against what `docs/`
says it does, first on its own and then together with every other feature. They also check that
the application still behaves exactly as it does without the starter.

Run them before every release. They are not part of the normal build or CI.

## Running

```bash
mvn verify -Pe2e
```

This builds the starter and runs every scenario on Spring Boot 3 and then Spring Boot 2, which takes
about 25 minutes. You need JDK 17 or newer, and ports **4317** and **4318** must be free (stop any
local collector or SigNoz first). Nothing else is required: Kafka, SQS (ElasticMQ) and the receiver
all run inside the test JVM.

```bash
# One Spring Boot version
mvn verify -Pe2e -pl signoz-e2e/signoz-e2e-boot3 -am

# One scenario class
mvn verify -Pe2e -pl signoz-e2e/signoz-e2e-boot3 -am -Dtest=FeatureIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Each service's console output is kept in `signoz-e2e/signoz-e2e-boot*/target/e2e-logs/<class>/<test>/{a,b}.log`.
The first line is the exact `java` command, so you can start a service by hand.

## How it works

| Piece | Where | What it does |
|---|---|---|
| Demo service | `app/` (shared), plus `signoz-e2e-boot*/src/main` for the SQS listener | One codebase, run as A or B. `ServletApp` (Spring MVC, STOMP, everything) and `ReactiveApp` (WebFlux on Netty). It uses the starter the way the docs tell users to: builders for HTTP clients, the interceptors for Kafka, SQS, gRPC and STOMP, a decorated `taskExecutor`, and the `@SigNozLog` compiler flags. |
| Scenarios | `scenarios/` (shared) | JUnit tests that start services, call them over HTTP and assert on their console JSON, their responses and what the receiver got. |
| Receiver | `signoz-e2e-support` | Accepts OTLP logs, traces and metrics over gRPC (4317) and HTTP (4318), and records headers. |
| Launcher | `signoz-e2e-support` | Starts each service in its own JVM with the module's runtime classpath, optionally with Log4j2 swapped in or the OpenTelemetry Java agent attached, and stops it with SIGTERM so exporters flush. |

The same app and scenario sources are compiled for both Spring Boot versions:
`signoz-e2e-boot3` (Java 17, `signoz-spring-boot3-starter`) and `signoz-e2e-boot2` (Java 8 target,
`signoz-spring-boot2-starter`).

## What is covered

**Each feature alone** (`FeatureIsolationTest`, `ReactiveTest`): every `signoz.*.enabled` switch is
off except the one under test, which also proves each switch turns its feature off.

| Feature | Checked |
|---|---|
| Starter off (`signoz.enabled=false`) | App unchanged: no JSON, no `[SigNoz]` lines, no headers, nothing exported |
| JSON console | Fields present, Spring Boot's bootstrap properties absent |
| OTLP log export | Records carry service, version, environment and the request's trace and span IDs; console untouched in `OTLP` mode |
| Trace identity | Inbound `traceparent` reused with a new span; `X-Request-ID` used alone; fresh IDs at the edge; invalid headers ignored |
| RestTemplate, WebClient | Trace and request ID reach the peer, also on a round trip A → B → A; outbound log line; nothing propagated when switched off |
| Kafka, SQS, gRPC, STOMP | The consumer's or handler's log lines carry the producer's trace (STOMP from the frame or from the handshake) |
| `@Async` | Worker thread keeps the request's IDs; switched off, it doesn't |
| `@Traced` | Span exported with its tags |
| HTTP access log | One line per request with status; bodies captured; excluded paths skipped; 401 and 404 logged with their real status |
| User context | `userId` and `userRoles` from Spring Security (HTTP Basic), on Spring MVC and WebFlux |
| `@AuditLog`, `@Masked` | Audit line with outcome and resource; the masked argument never logged, while the method gets the real value |
| `@Timed`, `@AlertOnFailure` | Durations, slow-call WARN with the threshold, failure WARN; the app keeps working |
| Error fingerprinting | Same `errorId` for the same failure; none when switched off |
| Slow queries | WARN with duration and SQL for slow statements only |
| Masking | Secrets in messages, JSON, structured arguments, MDC, query strings, headers (including cookies) and outbound URLs never reach the console or the export; `pci` profile and `field-strategies`; nothing masked when switched off |
| Sampling | INFO dropped at rate 0 while WARN and ERROR pass; every line kept without sampling |
| WebFlux | Trace IDs, access log, WebClient propagation, MDC kept across a thread hop (Spring Boot 3) |

**Together** (`CombinedFeaturesTest`, `ReactiveTest#everythingTogetherOnWebFlux`): every feature on,
both services, every channel, one trace through all of it. The same business script run with the
starter off and on must give identical results. A last scenario runs sampling at 0 with everything
else on and checks every WARN and ERROR still gets through.

**Export** (`ExportTest`): the defaults (no endpoint set), the SigNoz ingestion key (sent but never
printed), OTLP/HTTP with per-signal endpoints, Grafana Cloud (Basic auth, SigNoz key withheld),
Micrometer metrics from the starter's registry (its resource attributes, and the ingestion key on
Spring Boot 3), no metrics without the registry, and an unreachable collector that must not
slow down or break the app.

**Other setups**: Log4j2 instead of Logback (`Log4j2BackendTest`), and the OpenTelemetry Java agent
(`AgentModeTest`): the agent exports and propagates while the starter keeps its log features, and
with the agent's log export off, the starter exports masked logs itself.

Every scenario also checks that each service stays healthy: it answers, logged no unexpected ERROR,
had no class-loading errors, and created no SigNoz bean too early.

## Adding a scenario

Extend `E2eTestBase`. `startPair(options)` starts A and B pointing at each other, `startSingle`
starts one service, and `assertHealthy` runs the health checks. Use `ALL_FEATURES_OFF` as the base
when testing one feature alone. If the scenario needs a new endpoint, add it to both `ServletApp`
and `ReactiveApp` where it applies. Shared code must compile at Java 8 for the Spring Boot 2 module.
