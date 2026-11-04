# Changelog

## 1.1.0

### Upgrading from 1.0.x

- **Trace MDC keys are now `trace_id` / `span_id` / `trace_flags` on every path.**
  The Kafka, SQS, gRPC and STOMP consumers used to write camelCase `traceId` /
  `spanId` / `traceFlags` (the HTTP filter already wrote the snake_case keys).
  Update log patterns such as `%X{traceId}` and saved SigNoz queries. The starter
  still *reads* the camelCase keys as a fallback, so application code that writes
  them keeps propagating. (#5)
- **WebFlux apps on Spring Boot 3** get Reactor's automatic context propagation
  enabled by default, so handler logs keep their trace IDs after thread hops. Opt
  out with `signoz.web.reactive-context-propagation=false`. The Boot 3 module now
  depends on `io.micrometer:context-propagation` (small, no transitive
  dependencies). (#4)
- **The servlet `UserContextMdcFilter`** is registered only in servlet web apps; it
  was previously registered in every application. (#4)
- **Logback JSON console output fails closed:** if masking throws, the line's
  content is withheld instead of being written unmasked. (#8)
- **`signoz-ingestion-key` is never sent to a non-SigNoz backend** (when
  `signoz.exporter.backend` is not `signoz`); a warning is logged once. (#14)
- **`access_token`, `refresh_token`, `id_token` and `client_secret` are now masked by
  default**, wherever field masking applies, including query strings and bodies. The OAuth
  authorization `code` is not, because the name is too generic and masking it would
  hide error codes. To mask it, add `code` to `signoz.logging.masked-fields`, which
  replaces the default list, so include the other fields too.

### Added

- **Spring WebFlux support**: reactive `WebFilter`s for trace IDs
  (`X-Request-ID` / `traceparent` response headers), HTTP access logging with
  masked, bounded bodies, and user context from `ReactiveSecurityContextHolder`.
  `WebClient` propagation reads the request's IDs from the Reactor context. (#4)
- **Native Log4j2 support**: masked JSON console layout, OTLP log export, error
  fingerprinting and sampling, applied automatically when Log4j2 is the logging
  backend. (#6, merged via #17)
- **Grafana support** (Loki, Tempo, Mimir; Grafana Cloud or self-hosted): new
  `signoz.exporter.*` settings for the OTLP protocol (`grpc` or `http/protobuf`),
  a `backend` preset (`signoz`, `grafana-cloud`, `otlp`), per-signal endpoints and
  Basic auth, used by logs, traces and both metrics paths. Defaults are unchanged:
  OTLP gRPC to `signoz.endpoint`. (#14)
- **Different backends per environment** (for example dev → Grafana, prod →
  SigNoz) with Spring profiles or environment variables. (#14)
- **Logs linked to traces**: exported OTLP log records carry their trace and span
  IDs, so SigNoz and Grafana link logs to traces without the OTel agent. (#14)
- The startup summary prints the resolved export settings (never credentials).
  (#14)

### Fixed

- **Security:** the Logback JSON console output did not mask sensitive fields
  inside a JSON payload logged as the message (for example
  `log.info("login {}", body)`), and a message ending in `password=…` could
  truncate the rest of the JSON line. (#8)
- **Agentless trace propagation:** outbound RestTemplate, WebClient, gRPC, SQS and
  STOMP calls made while serving an HTTP request carried no `traceparent`, because
  the HTTP filter's snake_case MDC keys were not read. (#4)
- **Kafka without the OTel agent:** the producer sent no trace context unless an
  OTel span was active; the consumer now reads `traceparent` and falls back to the
  legacy headers. (#5)
- Malformed MDC values no longer produce an invalid `traceparent` header. (#5)
- WebFlux: MDC values from one request can no longer leak into another request's
  logs on the same thread. (#4)
- **Security:** the Logback JSON console output wrote lists and nested values
  under a sensitive key (for example `kv("token", List.of(a, b))`) in clear, and
  `token=[a, b]` in a message was masked only up to the first comma. With a
  custom prefix, arrays and objects were not masked at all. (#13)
- **Security:** HTTP request logging wrote the query string unmasked in the servlet
  and WebFlux filters, so a request such as `?token=abc` put the secret in the
  logs. `signoz.web.log-query-string` is on by default. Sensitive parameters are now
  masked using the same field names and strategies as bodies.
- **Security:** outbound RestTemplate and WebClient logging (`SIGNOZ_OUTBOUND`) wrote the full
  request URL, so `?access_token=abc` reached plain-text appenders in clear, and a client
  exception that quoted the URL repeated it. `signoz.outbound.log-requests` is on by default.
  The logged URL now has sensitive query parameters masked the same way as inbound requests,
  user info (`https://user:pass@host`) replaced with `***` and the fragment dropped; the URL
  inside a logged exception message is masked too.
- **Security:** field names matched only as listed, apart from case. `access_token` in
  `masked-fields` left `accessToken` and `access-token` in clear, and names no list
  anticipates, such as `auth_token`, `clientSecret` or `X-Amz-Signature`, were never masked.
  Names are now compared ignoring `_`, `-` and `.`, and any name ending in one of the new
  `signoz.logging.masked-field-suffixes` (`token`, `secret`, `password`, `passwd`, `apikey`,
  `signature`, `privatekey`) is masked in full unless it has its own entry. This applies
  wherever fields are masked by name: JSON, `key=value`, query strings, headers, MDC, XML and
  `ObjectMasker`. Set `masked-field-suffixes: []` to keep the previous behaviour; note that
  names such as `nextPageToken` are now masked by default.
- **WebClient on Spring Boot 3.2+:** with `signoz.outbound.log-requests` on (the
  default), every successful `WebClient` call failed with `NoSuchMethodError:
  ClientResponse.rawStatusCode()`, which Spring Framework 6.1 removed.
- **RestTemplate on Spring Framework 7 (Spring Boot 4):** the outbound interceptor
  called `ClientHttpResponse.getRawStatusCode()`, which Spring 7.0 removed, so with
  outbound logging on every `RestTemplate` call would fail with `NoSuchMethodError`.
  It is still present (deprecated for removal) in Spring 6.2. Both outbound clients
  now read the status in a way that works on Spring 5, 6 and 7, and logging the
  status can no longer fail the call.
- **Startup crash without Micrometer:** an application without Actuator or
  `micrometer-core` failed to start with `ClassNotFoundException: MeterRegistry`.
  `@Timed` and `@AlertOnFailure` now log without it, as documented; metrics are
  still recorded when a `MeterRegistry` bean exists.

### Internal

- Regression test for the `maskedArgumentAspect` circular reference (issue #1).
  (#3)
- Tests run on JDK 21 (Byte Buddy 1.18.14, test scope only). (#3)
- The servlet `TraceIdMdcFilter` shares its trace-ID logic with the WebFlux filter
  (`TraceIdentity`). (#5)
- Each Boot module also runs a test suite with Log4j2 as the real logging backend.
  (#6)
- The Logback JSON encoder no longer masks the message and MDC twice; a typical
  event encodes about 30% faster. (#13)
- Masking tests build planted secrets at runtime so secret scanners don't flag
  them. (#10, #11, #12, #15, #16)
