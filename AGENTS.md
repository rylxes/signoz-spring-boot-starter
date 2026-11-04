# AGENTS.md

Instructions for AI coding agents working in this repository. People should read
[CONTRIBUTING.md](CONTRIBUTING.md), which covers the same rules with more explanation.

## Project

A Spring Boot starter that exports logs, traces and metrics over OpenTelemetry (SigNoz, Grafana),
with JSON logging, sensitive-value masking and trace-ID propagation. Published as
`io.github.rylxes:signoz-spring-boot2-starter` and `…-boot3-starter`.

| Module | Java | Contents |
|---|---|---|
| `signoz-core` | 8 | Nearly all logic. Compiled against **Spring 5.3 / Reactor 3.4**, run on Spring 5.3, 6.x and (target) 7 |
| `signoz-spring-boot2` | 8 | Auto-configuration + `javax.servlet` filters for Boot 2.7 |
| `signoz-spring-boot3` | 17 | Same for Boot 3.2 (`jakarta.servlet`), plus `SigNozMdcThreadLocalAccessor` |
| `signoz-annotations`, `signoz-annotation-processor` | 8 | Annotations; `@SigNozLog` APT processor |
| `*-starter` | – | POM-only |

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before changing auto-configuration, agent
detection, aspects or masking.

## Commands

```bash
mvn clean verify --batch-mode                       # full build, what CI runs (JDK 17 and 21)
mvn -pl signoz-spring-boot3 -am verify              # one module plus its dependencies
mvn -pl signoz-spring-boot3 -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false
```

JDK 17+ is required for the full build. Each Boot module also runs `*Log4j2BackendTest` classes in a
separate `log4j2-backend` surefire execution. Run the full build before you commit.

## Rules

1. **Boot 2 and Boot 3 modules mirror each other.** Apply every change, including tests, to both.
   Files differ mainly by `javax.servlet` ↔ `jakarta.servlet`.
2. **No version-sensitive Spring calls in `signoz-core`.** It compiles against Spring 5.3, so a call
   to a method removed or retyped later compiles but throws `NoSuchMethodError` on Spring 6/7.
   Known cases: `ClientResponse.rawStatusCode()` (gone in 6.1), `ClientHttpResponse.getRawStatusCode()`
   (gone in 7.0), `statusCode()` / `getStatusCode()` (return type changed in 6.0). Use reflection
   resolved once with a fallback; see `io.signoz.springboot.outbound.ResponseStatusReader`. Verify
   with `javap` against the newer Spring jar if unsure.
3. **Java 8 source level** everywhere except `signoz-spring-boot3`: no `var`, `List.of`, records,
   switch expressions, text blocks.
4. **Optional dependencies stay optional.** Guard with `@ConditionalOnClass(name = "fully.qualified.Name")`
   (string form). The app must start without Micrometer, Spring Security, Kafka, AWS SDK, gRPC,
   WebSocket or Log4j2 on the classpath.
5. **Every `@Bean` gets `@ConditionalOnMissingBean`; every feature gets a
   `signoz.<feature>.enabled` property** (`matchIfMissing = true` unless opt-in).
6. **Agent-aware:** export and protocol propagation are skipped when the OpenTelemetry Java agent is
   present (`OnMissingAgentCondition`). Follow that for new propagation code.
7. **Masking fails closed.** Never fall back to logging a value unmasked; reject bad masking
   configuration at startup.
8. **Don't initialise the OTel SDK in tests.** Use `.withBean("openTelemetry", OpenTelemetry.class,
   OpenTelemetry::noop)` and `signoz.tracing.enabled=false`. `GlobalOpenTelemetry` is a JVM
   singleton.

## Tests

- Version-compatibility code: test with **real Spring objects** (`ClientResponse.create(...)`,
  `MockClientHttpResponse`, `MockRestServiceServer`), not Mockito mocks, and through the Boot 3
  module so it runs on Spring 6.
- Bug fixes: confirm the new test **fails without the fix** before committing.
- Masking tests: plant `private static final String CANARY = "s3cret";` and build fixtures around it
  at runtime; no credential-shaped literals (GitGuardian scans PRs).
- Log assertions: attach a Logback `ListAppender` to the logger (`SIGNOZ_HTTP`, `SIGNOZ_OUTBOUND`,
  `SIGNOZ_AUDIT`, …) in `@BeforeEach`, detach it in `@AfterEach`.

## Docs and changelog

- User-visible change → entry in `CHANGELOG.md` under the topmost version if it is unreleased (no
  `v<version>` git tag), else a new section. Headings: *Upgrading*, *Added*, *Fixed*, *Internal*.
- New or changed property → `docs/CONFIGURATION.md` and the guide in `docs/` for that area.
- Keep `README.md` short; details go in `docs/`.

## Commits

Subject style: `fix:`, `feat:`, `docs:`, `test:`, `refactor:`, `chore:`, `build:`, optional scope
(`fix(logging):`). Body says why the change was needed.
