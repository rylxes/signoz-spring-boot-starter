package io.signoz.springboot.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * gRPC {@link ServerInterceptor} that extracts the W3C {@code traceparent}
 * from inbound call {@link Metadata} and populates the SLF4J {@link MDC}
 * for the duration of the call. MDC is cleared on call close, cancel, or
 * a thrown exception.
 *
 * <p>Wire one into your server:
 * <pre>{@code
 * ServerBuilder.forPort(port)
 *     .intercept(tracingGrpcServerInterceptor)
 *     .addService(...)
 *     .build();
 * }</pre>
 *
 * <p>If the OpenTelemetry Java Agent is on the JVM, the agent already
 * propagates trace context for gRPC and this interceptor is not registered.
 */
public class TracingGrpcServerInterceptor implements ServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_GRPC");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        boolean populated = populateMdcFromHeaders(headers);

        ServerCall<ReqT, RespT> wrappedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void close(io.grpc.Status status, Metadata trailers) {
                        try {
                            super.close(status, trailers);
                        } finally {
                            if (populated) clearMdc();
                        }
                    }
                };

        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(wrappedCall, headers);
        } catch (RuntimeException ex) {
            if (populated) clearMdc();
            throw ex;
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
            @Override
            public void onCancel() {
                try {
                    super.onCancel();
                } finally {
                    if (populated) clearMdc();
                }
            }

            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } finally {
                    if (populated) clearMdc();
                }
            }
        };
    }

    private boolean populateMdcFromHeaders(Metadata headers) {
        String traceparent = headers.get(TracingGrpcClientInterceptor.TRACEPARENT_KEY);
        TraceContextCodec.Parsed parsed = TraceContextCodec.parse(traceparent);
        if (parsed == null) {
            return false;
        }
        MDC.put("traceId", parsed.traceId);
        MDC.put("spanId", parsed.spanId);
        MDC.put("traceFlags", parsed.traceFlags);

        String requestId = headers.get(TracingGrpcClientInterceptor.REQUEST_ID_KEY);
        MDC.put("requestId", (requestId != null && !requestId.isEmpty()) ? requestId : parsed.traceId);
        if (logger.isDebugEnabled()) {
            logger.debug("[SigNoz] Extracted traceparent from inbound gRPC call");
        }
        return true;
    }

    private static void clearMdc() {
        MDC.remove("traceId");
        MDC.remove("spanId");
        MDC.remove("traceFlags");
        MDC.remove("requestId");
    }
}
