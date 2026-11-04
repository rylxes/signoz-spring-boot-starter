# Web (Spring MVC and WebFlux)

The filters the starter adds to inbound HTTP requests: access logging, trace IDs and user context.
Properties are under `signoz.web.*` and `signoz.user-context.*` in the
[configuration reference](CONFIGURATION.md#web--http-logging-properties-signozweb).

- [HTTP access logging](#http-access-logging)
- [WebFlux applications](#webflux-applications)
- [User context](#user-context)

---

## HTTP access logging

Every inbound request produces one log line on the `SIGNOZ_HTTP` logger with the method, path,
query string, status and duration. Request and response headers and bodies can be included. They
go through the same masking as everything else (see [Masking](LOGGING.md#masking-sensitive-values)),
and bodies are cut off at `signoz.web.max-body-bytes` (default 4096).

Headers are logged only with `signoz.web.log-headers: true`, and are masked by name with the same
rules as other fields: `Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token` and
any name ending in `token`, `secret`, `signature` and so on. If you set
`signoz.logging.masked-fields`, keep `cookie` and `set-cookie` in your list (it replaces the
defaults), or mask further headers with `field-strategies`, which adds to them:

```yaml
signoz:
  logging:
    field-strategies:
      x-session-id: full
```

**Exclude paths** (defaults: `/actuator/**`, `/health`, `/health/**`, `/favicon.ico`). Setting the
list replaces the defaults:

```yaml
signoz:
  web:
    exclude-paths:
      - /actuator/**
      - /internal/**
      - /metrics
```

**Turn off body capture** on high-volume services:

```yaml
signoz:
  web:
    log-request-body: false   # default: true
    log-response-body: false  # default: false
```

Turn the access log off entirely with `signoz.web.log-requests: false`.

---

## WebFlux applications

In a Spring WebFlux application the starter registers reactive `WebFilter`s instead of the servlet
filters. All `signoz.web.*` and `signoz.user-context.*` properties apply unchanged.

| Spring MVC filter | WebFlux filter | Order |
|---|---|---|
| `TraceIdMdcFilter` | `TraceIdWebFilter` | `HIGHEST_PRECEDENCE + 1` |
| `HttpLoggingFilter` | `HttpLoggingWebFilter` | `HIGHEST_PRECEDENCE + 2` |
| `UserContextMdcFilter` | `UserContextWebFilter` | `0` (after Spring Security's `-100`) |
| – | `MdcRestoringWebFilter` | `LOWEST_PRECEDENCE` (re-applies the IDs if an earlier filter switched threads) |

What you get per request:

- `X-Request-ID` and `traceparent` response headers, chosen
  [the same way](TRACING.md#how-a-requests-identity-is-chosen) as in Spring MVC.
- One `SIGNOZ_HTTP` access-log line. Failed requests are logged with the status the error handler
  actually sent (for example 404 for a `ResponseStatusException`), plus `error=<exception class>`.
- `requestId`, `trace_id`, `span_id`, `trace_flags` (and the user fields for authenticated users)
  in the MDC of your handler's logs, and in the Reactor context under `ReactiveMdc.CONTEXT_KEY`.
- Outbound `WebClient` calls carry the request's `traceparent` and `X-Request-ID`, even after
  thread hops.

**MDC across thread hops.** Reactive handlers often continue on another thread (`publishOn`, a
`WebClient` response, a reactive driver).

- **Spring Boot 3:** the starter enables Reactor's automatic context propagation (the same switch
  as `spring.reactor.context-propagation=auto`) for the SigNoz MDC keys, so every log line in the
  pipeline keeps its IDs. To opt out:

  ```yaml
  signoz:
    web:
      reactive-context-propagation: false
  ```

- **Spring Boot 2** (Reactor 3.4 has no automatic propagation), or with the switch above off: the
  MDC is set until the handler's first thread hop. After that, log through `ReactiveMdc`, which
  reads the IDs from the Reactor context:

  ```java
  return webClient.get().uri("/inventory").retrieve().bodyToMono(Stock.class)
          .doOnEach(ReactiveMdc.onNext(stock -> log.info("stock {}", stock.count())));
  ```

---

## User context

When Spring Security is on the classpath, the starter puts the authenticated user into the MDC, so
every log line for the request carries `userId`, `userEmail` and `userRoles`. It uses reflection,
so Spring Security isn't a required dependency. In WebFlux the same fields are read from
`ReactiveSecurityContextHolder` by `UserContextWebFilter`, which runs after Spring Security's
filter chain.

```yaml
signoz:
  user-context:
    enabled: true
    extract-email: true
    extract-roles: true
    principal-field: email   # field on the principal used as userId
```
