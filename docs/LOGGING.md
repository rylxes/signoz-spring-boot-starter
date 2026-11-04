# Logging

What the starter does to your logs: where they go, what's on each line, how sensitive values are
masked, and how to tune volume. Properties are listed in full in the
[configuration reference](CONFIGURATION.md#logging-properties-signozlogging).

- [Output: console JSON and OTLP export](#output-console-json-and-otlp-export)
- [Fields on each log line](#fields-on-each-log-line)
- [Logback and Log4j2](#logback-and-log4j2)
- [Using your own logback-spring.xml](#using-your-own-logback-springxml)
- [Masking sensitive values](#masking-sensitive-values)
- [Log sampling](#log-sampling)
- [Error fingerprinting](#error-fingerprinting)

---

## Output: console JSON and OTLP export

`signoz.logging.mode` picks where logs go:

| Mode | Console | OTLP export |
|---|---|---|
| `BOTH` (default) | JSON | Yes |
| `JSON` | JSON | No |
| `OTLP` | Unchanged | Yes |

In JSON mode the starter replaces Spring Boot's default console appender (`CONSOLE` for Logback,
`Console` for Log4j2) unless your configuration already writes JSON. Each event becomes one JSON
object with `@timestamp`, `message`, `logger`, `thread`, `level`, the MDC fields below, and any
structured arguments you log.

When the [OpenTelemetry Java agent](EXPORTING.md#with-the-opentelemetry-java-agent) exports logs,
the starter leaves OTLP log export to it.

**Clean by default.** Spring Boot publishes internal bootstrap variables (`CONSOLE_LOG_PATTERN`,
`FILE_LOG_PATTERN`, `PID`, …) into the Logback `LoggerContext`, and Logstash's encoder would emit
them as top-level fields on every line. The starter turns that off. If you publish your own
`LoggerContext` properties and want them on every event, opt back in:

```yaml
signoz:
  logging:
    include-context: true   # default: false
```

---

## Fields on each log line

These MDC fields are added for you, and appear in the JSON output and on exported log records:

| Field | When | Source |
|---|---|---|
| `requestId` | During an HTTP request, or a Kafka / SQS / gRPC / STOMP message | Inbound `X-Request-ID`, else the trace ID |
| `trace_id`, `span_id`, `trace_flags` | Same as above | Active span, else inbound `traceparent`, else new IDs. See [Tracing](TRACING.md#how-a-requests-identity-is-chosen) |
| `userId`, `userEmail`, `userRoles` | Authenticated requests, when Spring Security is present | See [User context](WEB.md#user-context) |
| `errorId` | ERROR events with an exception | See [Error fingerprinting](#error-fingerprinting) |

The trace keys use the same snake_case names as the OpenTelemetry agent, so one SigNoz query finds
a trace's logs whichever protocol each hop used.

> **Upgrading from 1.0.x:** the Kafka, SQS, gRPC and STOMP consumers used to write camelCase
> `traceId` / `spanId` / `traceFlags`. Update log patterns such as `%X{traceId}` and saved
> queries. See the [changelog](../CHANGELOG.md).

---

## Logback and Log4j2

Every logging feature works on both Logback (Spring Boot's default) and Log4j2. The starter detects
the active backend at startup and configures that one.

| Feature | Logback | Log4j2 |
|---|---|---|
| JSON console output | `SigNozJsonEncoder` (`SIGNOZ_JSON`) | `SigNozLog4j2JsonLayout` (`SIGNOZ_JSON`), same field names |
| Masking | Yes | Yes, applied to each value before serialisation |
| OTLP log export | `OtlpLogbackAppender` (`SIGNOZ_OTLP`) | `OtlpLog4j2Appender` (`SIGNOZ_OTLP`) |
| Error fingerprinting (`errorId`) | In the MDC, via a turbo filter | Added to the JSON line and OTLP record for ERROR events with an exception |
| Log sampling | Turbo filter | Context-wide Log4j2 filter |
| MDC trace fields | Yes | Yes (the SLF4J MDC is Log4j2's ThreadContext) |

To switch a project to Log4j2, exclude Spring Boot's default logging starter and add
`spring-boot-starter-log4j2`:

```xml
<dependency>
    <groupId>io.github.rylxes</groupId>
    <artifactId>signoz-spring-boot3-starter</artifactId>
    <version>1.1.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-log4j2</artifactId>
</dependency>
```

As with any Spring Boot Log4j2 setup, exclude `spring-boot-starter-logging` from every other
starter that pulls it in, so Logback and `log4j-to-slf4j` are not on the classpath.

---

## Using your own logback-spring.xml

Include the bundled configuration and reference its appenders:

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

`logback-signoz.xml` activates the JSON encoder and OTLP appender according to
`signoz.logging.mode`, and sets `<includeContext>false</includeContext>` on the encoder (see
[Clean by default](#output-console-json-and-otlp-export)).

---

## Masking sensitive values

Masking applies wherever the starter writes a value: log messages, JSON payloads in messages,
structured arguments, MDC values, HTTP headers, query strings and bodies, outbound URLs and audit
entries. It never changes the values your code sees. Switch it off entirely with
`signoz.logging.mask-enabled: false`.

**Masked by default.** These field names are replaced with `***`:
`password`, `passwd`, `secret`, `token`, `apikey`, `api_key`, `creditcard`, `cardnumber`,
`card_number`, `cvv`, `ssn`, `authorization`, `x-api-key`, `x-auth-token`, `access_token`,
`refresh_token`, `id_token`, `client_secret`. Free-text patterns also catch `password=…`,
`Bearer …` tokens, 16-digit card numbers and US SSNs in messages.

**How names match.** Names are compared ignoring case, `_`, `-` and `.`, so `access_token` also
covers `accessToken` and `access-token`. Any other name *ending* in `token`, `secret`, `password`,
`passwd`, `apikey`, `signature` or `privatekey` is masked too (`auth_token`, `clientSecret`,
`X-Amz-Signature`); names that only start with or contain those words (`tokenType`,
`passwordPolicy`) stay visible. Change the endings with `signoz.logging.masked-field-suffixes`, or
set it to `[]` to turn suffix matching off. Pagination cursors such as `nextPageToken` are masked
by this rule.

**Adding fields.** `signoz.logging.masked-fields` *replaces* the default list, so copy the
defaults you want to keep:

```yaml
signoz:
  logging:
    masked-fields:
      - password
      - token
      - authorization
      # …the rest of the defaults you want…
      - myPrivateField
```

**Keeping part of a value.** `signoz.logging.field-strategies` sets the strategy for one field:
`full`, `partial:<prefix>:<suffix>` (keep that many leading and trailing characters), `partial`
(same as `partial:2:2`) or `regex:<pattern>` (mask every match). An invalid spec fails startup
rather than silently disabling masking.

```yaml
signoz:
  logging:
    field-strategies:
      cardPan: "partial:6:4"    # 506105******6567
      clearPin: "full"
```

**Curated profiles.** `signoz.logging.profiles` adds field sets for common compliance needs:
`pci` (card numbers keep BIN and last four; PIN, track, chip data, CVV and expiry are masked whole)
and `pii` (government IDs such as SSN, BVN, NIN, tax and passport numbers; bank account numbers;
email, phone and address; date of birth; personal names). Profiles are applied on top of
`masked-fields`, and `field-strategies` wins over both.

```yaml
signoz:
  logging:
    profiles: [pci, pii]
```

**Custom patterns** mask text in free-form messages:

```yaml
signoz:
  logging:
    custom-patterns:
      - name: awsKey
        regex: "AKIA[A-Z0-9]{16}"   # the whole match becomes ***
```

For code-level options (`@Masked` on parameters, a custom `MaskingRegistry` bean) see
[Annotations](ANNOTATIONS.md#masked--sensitive-parameters) and
[Customizing](CUSTOMIZING.md#custom-masking-registry).

---

## Log sampling

Reduce log volume in high-traffic environments. ERROR and WARN always pass; other levels are kept
with the given probability. Off by default.

```yaml
signoz:
  logging:
    sampling:
      enabled: true
      rate: 0.1               # keep 10% of INFO/DEBUG logs
      always-log-levels:
        - ERROR
        - WARN
```

---

## Error fingerprinting

When you log at ERROR with an exception, the starter adds an `errorId`: a SHA-256 hash of the
exception type and its top stack frames. The same failure always gets the same ID, so you can group
and count identical errors in SigNoz.

```yaml
signoz:
  errors:
    enabled: true
    fingerprint-depth: 3   # stack frames included in the hash
```
