# Tracing and correlation

How the starter keeps one trace ID on a request as it moves between threads and services, so a
single SigNoz query finds every log line for it. Properties are in the
[configuration reference](CONFIGURATION.md).

- [With or without the OpenTelemetry agent](#with-or-without-the-opentelemetry-agent)
- [How a request's identity is chosen](#how-a-requests-identity-is-chosen)
- [Outbound HTTP](#outbound-http)
- [Kafka](#kafka)
- [SQS](#sqs)
- [gRPC](#grpc)
- [WebSocket / STOMP](#websocket--stomp)
- [Async threads](#async-threads)
- [Reactive applications](#reactive-applications)
- [Spans for your own methods](#spans-for-your-own-methods)

---

## With or without the OpenTelemetry agent

**Without the agent** the starter gives you *log correlation*. Each service reuses the inbound trace
ID, writes it to every log line and forwards it on outbound calls. Filter by `trace_id=…` in SigNoz
and you see every log across the whole transaction. You do **not** get span waterfalls, per-hop
timing or the SigNoz trace view: those need real spans from the
[OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/), the
[OpenTelemetry Spring Boot starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/),
or Spring Cloud Sleuth with the OTel bridge.

**With the agent** you get both. The agent creates spans and propagates context over every
protocol; the starter detects it, skips its own propagation beans (no double instrumentation) and
uses the agent's trace IDs for its log fields. See
[Exporting with the agent](EXPORTING.md#with-the-opentelemetry-java-agent).

You can start without the agent and add it later: its trace IDs take priority, and the starter's
fallback becomes a no-op.

> **Every hop has to take part.** End-to-end correlation works only when each service on the path
> honours an inbound `traceparent` and forwards it. Install the starter (or the agent) on all of
> them.

---

## How a request's identity is chosen

For each inbound HTTP request, `TraceIdMdcFilter` (Spring MVC) or `TraceIdWebFilter` (WebFlux)
picks the trace identity from the first of these that exists:

1. **The active OpenTelemetry span**, when the agent or SDK has one.
2. **An inbound W3C `traceparent` header.** It is validated (`00-<32 hex>-<16 hex>-<2 hex>`), the
   trace ID and flags are reused, and a new span ID is generated for this hop.
3. **An inbound `X-Request-ID` header**, for callers that don't speak W3C. It is normalised to 32 hex
   characters so it can be used as a trace ID.
4. **New IDs**, when this service is the edge of the system.

The filter writes `requestId`, `trace_id`, `span_id` and `trace_flags` to the MDC (the trace keys
are left to the agent when it owns them), clears them when the request ends, and returns both
`traceparent` and `X-Request-ID` on the response. The Kafka, SQS, gRPC and STOMP consumers below
write the same keys.

---

## Outbound HTTP

RestTemplate and WebClient calls get a W3C `traceparent` header and a matching `X-Request-ID`, and
each call is logged with its URL (sensitive query parameters masked), status and duration:

```
[SigNoz] Outbound GET https://api.example.com/users/123 -> 200 in 87ms
```

**Build your clients from Spring Boot's builders.** The starter registers a
`RestTemplateCustomizer` and a `WebClientCustomizer`, which Spring Boot applies to clients built
from its `RestTemplateBuilder` and `WebClient.Builder` beans. A client created with
`new RestTemplate()` or `WebClient.create()` is not traced.

```java
@Bean
RestTemplate restTemplate(RestTemplateBuilder builder) {   // traced
    return builder.build();
}

@Bean
WebClient webClient(WebClient.Builder builder) {           // traced
    return builder.baseUrl("https://api.example.com").build();
}
```

The propagated identity comes from the active OTel span; for WebClient in a WebFlux app, from the
request's IDs in the Reactor context (so it survives thread hops); otherwise from the MDC written by
the inbound filter.

```yaml
signoz:
  outbound:
    enabled: true            # default
    log-requests: true       # log each outbound call
    propagate-headers: true  # add traceparent + X-Request-ID
```

---

## Kafka

Kafka creates its interceptors itself, so register them in your Kafka properties:

```yaml
spring:
  kafka:
    producer:
      properties:
        interceptor.classes: io.signoz.springboot.messaging.TracingProducerInterceptor
    consumer:
      properties:
        interceptor.classes: io.signoz.springboot.messaging.TracingConsumerInterceptor
```

The producer adds `traceparent` (plus the legacy `traceId` / `spanId` headers for consumers on
older versions of the starter) to each record, from the active span or, without an agent, the MDC.
The consumer reads `traceparent`, falls back to the legacy headers, and writes `trace_id` /
`span_id` / `trace_flags` to the MDC. With the agent present, the consumer leaves the MDC to the
agent's Kafka instrumentation.

```yaml
signoz:
  messaging:
    enabled: true
    propagate-trace: true
```

---

## SQS

> Skipped automatically when the OpenTelemetry Java agent is present; the agent instruments SQS.

**Producer:** add the `TracingSqsRequestHandler` bean (AWS SDK v1) to your SQS client. It puts
`traceparent` in the message attributes.

```java
@Bean
AmazonSQSAsync sqs(TracingSqsRequestHandler tracingHandler) {
    return AmazonSQSAsyncClientBuilder.standard()
        .withRequestHandlers(tracingHandler)   // ← add this line
        .withRegion(Regions.EU_WEST_1)
        .build();
}
```

**Consumer:** nothing to do. An aspect runs around every `@SqsListener` method; the starter detects
which `@SqsListener` you use:

| `@SqsListener` package | Library |
|---|---|
| `io.awspring.cloud.messaging.listener.annotation` | `io.awspring.cloud:spring-cloud-aws-messaging` 2.x |
| `io.awspring.cloud.sqs.annotation` | `io.awspring.cloud:spring-cloud-aws-sqs` 3.x |
| `org.springframework.cloud.aws.messaging.listener.annotation` | legacy `spring-cloud-starter-aws-messaging` |

The aspect reads the trace context from a `Message<?>`, `MessageHeaders` or
`@Headers Map<String, Object>` parameter. A listener that takes only the payload can't be
correlated: add one of those parameters, or call `SqsTraceContext.populateMdcFromHeaders(headers)`
yourself.

```java
@SqsListener("my-queue")
public void onMessage(@Payload MyDto body, @Headers Map<String, Object> headers) {
    log.info("processing");   // carries the upstream trace_id
}
```

```yaml
signoz:
  sqs:
    enabled: true
    propagate-trace: true
```

---

## gRPC

> Skipped automatically when the OpenTelemetry Java agent is present; the agent wires its own gRPC
> interceptors.

The starter provides two beans but can't attach them for you, because gRPC has no Spring hook like
`RestTemplateCustomizer`:

- `TracingGrpcClientInterceptor` adds `traceparent` to outgoing `Metadata`.
- `TracingGrpcServerInterceptor` reads `traceparent` from inbound `Metadata` and fills the MDC for
  the call.

```java
@Bean
ManagedChannel myServiceChannel(TracingGrpcClientInterceptor tracing) {
    return ManagedChannelBuilder.forAddress("svc-c", 9090)
        .intercept(tracing)
        .usePlaintext()
        .build();
}

@Bean
Server grpcServer(TracingGrpcServerInterceptor tracing, MyService service) {
    return ServerBuilder.forPort(9090)
        .intercept(tracing)
        .addService(service)
        .build();
}
```

With [`net.devh:grpc-spring-boot-starter`](https://github.com/yidongnan/grpc-spring-boot-starter),
annotate the bean methods that return these interceptors with `@GrpcGlobalClientInterceptor` /
`@GrpcGlobalServerInterceptor` and they apply to every channel and server.

> **MDC inside the handler is best-effort.** gRPC may run your handler on a different thread from
> the interceptor. The `traceparent` always reaches the wire; with a thread-pool executor the MDC
> in your handler may be empty. If you need it there, read the context from `Metadata` at the start
> of the method.

```yaml
signoz:
  grpc:
    enabled: true
    propagate-trace: true
```

---

## WebSocket / STOMP

> Skipped automatically when the OpenTelemetry Java agent is present.

**Frames: nothing to do.** The starter adds a `ChannelInterceptor` to the client-inbound and
client-outbound channels through its own `WebSocketMessageBrokerConfigurer`, which Spring combines
with yours. Inbound frames' `traceparent` native header fills the MDC; outbound frames get the
current `traceparent`.

**Handshake: add one interceptor** so the HTTP upgrade's `traceparent` is kept for frames that
don't carry one:

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws")
        .addInterceptors(tracingWebSocketHandshakeInterceptor)   // ← add this
        .withSockJS();
}
```

Raw (non-STOMP) WebSocket messages aren't covered: put the trace context in your message envelope,
using `TraceContextCodec.currentTraceparent()` to get it.

```yaml
signoz:
  websocket:
    enabled: true
    propagate-trace: true
```

---

## Async threads

The MDC is thread-local, so `@Async` methods and executor tasks lose `requestId` and `trace_id`
when they switch threads. The starter's `TracingTaskDecorator` copies the MDC to the worker thread.

- **Spring Boot's auto-configured executor** (used by `@Async` by default): nothing to do.
- **A `ThreadPoolTaskExecutor` you create:** set the decorator yourself.
- **A plain `ExecutorService`:** wrap it.

```java
@Bean
ThreadPoolTaskExecutor reportExecutor(TracingTaskDecorator tracingTaskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(tracingTaskDecorator);
    return executor;
}

ExecutorService traced = new TracingExecutorService(Executors.newFixedThreadPool(10));
```

```yaml
signoz:
  async:
    enabled: true
```

---

## Reactive applications

In WebFlux the reactive filters keep the request's IDs in the Reactor context, so outbound
`WebClient` calls carry the right `traceparent` even after thread hops. On Spring Boot 3 the MDC
also follows the request across threads; on Spring Boot 2 it lasts until the first hop. See
[WebFlux](WEB.md#webflux-applications).

---

## Spans for your own methods

`@Traced` wraps a method in an OpenTelemetry span. See [Annotations](ANNOTATIONS.md#traced--spans).
