package io.signoz.springboot.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * gRPC {@link ClientInterceptor} that injects the W3C {@code traceparent} (and
 * forwards {@code X-Request-ID}) into the outgoing call's {@link Metadata} so
 * the server side can continue the trace.
 *
 * <p>Wire one into your channel:
 * <pre>{@code
 * ManagedChannelBuilder.forAddress(host, port)
 *     .intercept(tracingGrpcClientInterceptor)
 *     .build();
 * }</pre>
 *
 * <p>If the OpenTelemetry Java Agent is on the JVM, the agent already
 * propagates trace context for gRPC and this interceptor is not registered.
 */
public class TracingGrpcClientInterceptor implements ClientInterceptor {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_GRPC");

    static final Metadata.Key<String> TRACEPARENT_KEY =
            Metadata.Key.of(TraceContextCodec.TRACEPARENT_HEADER, Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> REQUEST_ID_KEY =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String traceparent = TraceContextCodec.currentTraceparent();
                if (traceparent != null) {
                    headers.put(TRACEPARENT_KEY, traceparent);
                }
                String requestId = MDC.get("requestId");
                if (requestId != null && !requestId.isEmpty()) {
                    headers.put(REQUEST_ID_KEY, requestId);
                }
                if (logger.isDebugEnabled() && traceparent != null) {
                    logger.debug("[SigNoz] Injected traceparent into gRPC call {}", method.getFullMethodName());
                }
                super.start(responseListener, headers);
            }
        };
    }
}
