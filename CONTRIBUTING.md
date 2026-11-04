# Contributing

Thanks for helping improve the SigNoz Spring Boot Starter. This guide covers how to build the
project, the rules that keep it working across Spring versions, and what a pull request needs.

If you're an AI coding agent, read [AGENTS.md](AGENTS.md) instead: it covers the same rules in a
compact form.

- [Getting set up](#getting-set-up)
- [How the project is laid out](#how-the-project-is-laid-out)
- [Building and testing](#building-and-testing)
- [Rules that keep the starter compatible](#rules-that-keep-the-starter-compatible)
- [Writing tests](#writing-tests)
- [Documentation and changelog](#documentation-and-changelog)
- [Commits and pull requests](#commits-and-pull-requests)

---

## Getting set up

You need **JDK 17 or newer** and **Maven 3**. JDK 17 is required because the Spring Boot 3
module compiles at Java 17; every other module compiles at Java 8 and runs on any JDK 8+. CI builds
on JDK 17 and 21.

```bash
git clone https://github.com/rylxes/signoz-spring-boot-starter.git
cd signoz-spring-boot-starter
mvn clean verify --batch-mode
```

A full build with tests takes about a minute once dependencies are downloaded.

---

## How the project is laid out

| Module | Java | What's in it |
|---|---|---|
| `signoz-annotations` | 8 | Annotation types only; no runtime dependencies |
| `signoz-annotation-processor` | 8 | Compile-time processor for `@SigNozLog` |
| `signoz-core` | 8 | Almost all logic: masking, tracing, logging, audit, metrics, outbound and messaging propagation, WebFlux filters. Compiled against Spring 5.3 |
| `signoz-spring-boot2` | 8 | Auto-configuration and servlet filters for Spring Boot 2 (`javax.servlet`) |
| `signoz-spring-boot3` | 17 | The same for Spring Boot 3 (`jakarta.servlet`) |
| `signoz-spring-boot2-starter`, `signoz-spring-boot3-starter` | – | POM-only starters that users depend on |

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the pieces fit together: the
auto-configuration load order, agent detection, aspect ordering and the masking pipeline.

---

## Building and testing

```bash
# Everything, as CI runs it
mvn clean verify --batch-mode

# One module and what it depends on
mvn -pl signoz-spring-boot3 -am verify

# One test class
mvn -pl signoz-spring-boot3 -am test -Dtest=SigNozOutboundAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false

# Only the Java 8 modules (no JDK 17 needed)
mvn clean install -pl signoz-annotations,signoz-annotation-processor,signoz-core,signoz-spring-boot2,signoz-spring-boot2-starter
```

Each Boot module runs its tests twice: once with Logback (the default execution) and once with
Log4j2 as the real logging backend (the `log4j2-backend` execution, which runs only
`*Log4j2BackendTest` classes).

**End-to-end tests** start two demo services that call each other over HTTP, Kafka, SQS, gRPC and
STOMP, on Spring Boot 2 and 3, and check every feature against the docs: alone, all together,
on Log4j2 and with the OpenTelemetry Java agent. They take about 25 minutes and need ports 4317
and 4318 free, so they are not part of the normal build. Run them before a release, and when you
change how a feature behaves at runtime:

```bash
mvn verify -Pe2e
```

See [signoz-e2e/README.md](signoz-e2e/README.md) for what they cover and how to add a scenario.

---

## Rules that keep the starter compatible

The same `signoz-core` jar runs on Spring Framework 5.3, 6.x and, looking ahead, 7. Most bugs in
this project's history come from breaking one of these rules.

**1. Keep the Boot 2 and Boot 3 modules in step.** They hold the same classes and tests, differing
mainly in `javax.servlet` vs `jakarta.servlet`. A change to one almost always belongs in the other,
tests included. The only Boot 3-only class is `SigNozMdcThreadLocalAccessor`.

**2. Don't call Spring APIs that changed between versions from `signoz-core`.** Code there is
compiled against Spring 5.3. A method that was removed or changed its return type in Spring 6 or 7
compiles fine but throws `NoSuchMethodError` at runtime on newer Spring. Examples:
`ClientResponse.rawStatusCode()` (removed in 6.1), `ClientHttpResponse.getRawStatusCode()` and
`ServerHttpResponse.getRawStatusCode()` (removed in 7.0), any `getStatusCode()` / `statusCode()`
(returns `HttpStatus` on 5, `HttpStatusCode` on 6+), and the map methods of `HttpHeaders`
(`entrySet()`, `get(Object)`, …), which is no longer a map in Spring 7; use `getFirst(String)` or
`toSingleValueMap()`, which exist on every version. Resolve changed methods reflectively, once,
with a fallback: `io.signoz.springboot.compat.ResponseStatusReader` does this for response
statuses. When in doubt, check the method with `javap` against the newer Spring jar.

**3. Keep Java 8 source compatibility** in every module except `signoz-spring-boot3`: no `var`,
`List.of`, records, text blocks or other post-8 APIs.

**4. Treat integrations as optional.** Spring Boot, Micrometer, Spring Security, Kafka, AWS, gRPC,
WebSocket and Log4j2 are `optional` dependencies. Guard auto-configurations with
`@ConditionalOnClass(name = "…")` (a string, so the class is never loaded when absent), and don't
let a class that references an optional type load unless that type is present. The app must start
with none of them on the classpath; `MicrometerOptionalAutoConfigurationTest` checks this for
Micrometer.

**5. Let users override everything.** Every `@Bean` in an auto-configuration has
`@ConditionalOnMissingBean`, and every feature has a `signoz.<feature>.enabled` switch.

**6. Step aside for the OpenTelemetry agent.** Protocol propagation (SQS, gRPC, WebSocket) and OTLP
export are skipped when the agent is detected (`OnMissingAgentCondition`). New propagation features
should follow the same pattern so nothing is instrumented twice.

**7. Masking fails closed.** If masking throws, withhold the value rather than log it in clear. A
bad masking configuration should fail at startup, not silently disable masking.

---

## Writing tests

- **Test against real Spring objects, not mocks, where versions differ.** A Mockito mock of a Spring
  interface hides a method that no longer exists. Build real responses (`ClientResponse.create(…)`,
  `MockClientHttpResponse`, `MockRestServiceServer`) and run the code through the Boot 3 module so
  it executes on Spring 6.
- **Mirror tests in both Boot modules** (rule 1).
- **Don't start the OpenTelemetry SDK in tests.** `GlobalOpenTelemetry` can only be set once per
  JVM. Supply `OpenTelemetry.noop()` and set `signoz.tracing.enabled=false`; see the
  [OTel SDK singleton hazard](docs/ARCHITECTURE.md#otel-sdk-singleton-hazard).
- **Plant fake secrets as a `CANARY` constant.** Masking tests use an obviously fake value
  (`private static final String CANARY = "s3cret";`) and assemble JSON or `key=value` fixtures
  around it at runtime (for example with a `jsonObject(...)` helper) rather than writing
  credential-shaped string literals, so GitGuardian, which scans every PR, doesn't flag them. Assert
  that the canary is absent from the output.
- **Capture logs with a Logback `ListAppender`** on the logger under test (`SIGNOZ_HTTP`,
  `SIGNOZ_OUTBOUND`, `SIGNOZ_AUDIT`, …), and detach it in `@AfterEach`.
- **For a bug fix, show the test failing first.** Revert the fix locally, confirm the new test fails
  for the reason you expect, then restore it.

---

## Documentation and changelog

- **CHANGELOG.md:** every user-visible change gets an entry in the topmost version section if that
  version hasn't been released (no `v<version>` tag yet); otherwise start a new section. Use the
  existing headings: *Upgrading*, *Added*, *Fixed*, *Internal*. Write for users: what was wrong or
  what they can now do, and what they need to change.
- **New or changed properties** go in [docs/CONFIGURATION.md](docs/CONFIGURATION.md) and in the
  guide for that area under [docs/](docs/).
- **README.md** is the landing page: what the starter does, a quick start, and links. Put details in
  the guides rather than the README.

---

## Commits and pull requests

Commit subjects follow a light [Conventional Commits](https://www.conventionalcommits.org/) style:
`fix:`, `feat:`, `docs:`, `test:`, `refactor:`, `chore:`, `build:`, with an optional scope such as
`fix(logging):`. The body explains why the change was needed, not just what it does.

Before opening a pull request, check that:

- [ ] `mvn clean verify --batch-mode` passes (ideally on JDK 17 and 21, as CI does)
- [ ] For runtime behaviour changes: `mvn verify -Pe2e` passes
- [ ] Boot 2 and Boot 3 modules received the same change, tests included
- [ ] Bug fixes have a test that failed before the fix
- [ ] CHANGELOG.md has an entry for any user-visible change
- [ ] New properties are in docs/CONFIGURATION.md and the relevant guide

CI runs the build on JDK 17 and 21 plus a GitGuardian secret scan. Automated reviewers may comment;
answer or resolve their threads like any other review.
