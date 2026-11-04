package io.signoz.e2e.app.common;

import io.grpc.CallOptions;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.signoz.springboot.annotation.SigNozLog;
import io.signoz.springboot.grpc.TracingGrpcClientInterceptor;
import io.signoz.springboot.grpc.TracingGrpcServerInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * A one-method gRPC service ({@code e2e.Echo/Say}, string in, string out) and its client. The
 * starter's interceptors are wired the way the tracing guide shows, when the starter provides them.
 */
@SigNozLog
@Component
public class GrpcEcho implements SmartLifecycle {

    static final MethodDescriptor<String, String> SAY = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("e2e.Echo", "Say"))
            .setRequestMarshaller(new Utf8())
            .setResponseMarshaller(new Utf8())
            .build();

    private final int port;
    private final int peerPort;
    private final Observations observations;
    private final ObjectProvider<TracingGrpcServerInterceptor> serverInterceptor;
    private final ObjectProvider<TracingGrpcClientInterceptor> clientInterceptor;
    private volatile Server server;

    public GrpcEcho(@Value("${e2e.grpc.port:0}") int port,
                    @Value("${e2e.peer-grpc-port:0}") int peerPort,
                    Observations observations,
                    ObjectProvider<TracingGrpcServerInterceptor> serverInterceptor,
                    ObjectProvider<TracingGrpcClientInterceptor> clientInterceptor) {
        this.port = port;
        this.peerPort = peerPort;
        this.observations = observations;
        this.serverInterceptor = serverInterceptor;
        this.clientInterceptor = clientInterceptor;
    }

    /** Calls the peer's {@code Say} and returns its reply (the peer's trace ID). */
    public String callPeer(String message) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", peerPort).usePlaintext().build();
        try {
            Channel intercepted = channel;
            ClientInterceptor tracing = clientInterceptor.getIfAvailable();
            if (tracing != null) {
                intercepted = ClientInterceptors.intercept(channel, tracing);
            }
            return ClientCalls.blockingUnaryCall(intercepted, SAY, CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS), message);
        } finally {
            channel.shutdownNow();
        }
    }

    @Override
    public void start() {
        if (port <= 0) {
            return;
        }
        ServerServiceDefinition service = ServerServiceDefinition.builder("e2e.Echo")
                .addMethod(SAY, ServerCalls.asyncUnaryCall((ServerCalls.UnaryMethod<String, String>) (request, response) -> {
                    log.info("grpc received {}", request);
                    observations.record("grpc", Collections.singletonMap("payload", request));
                    String traceId = Observations.mdc().get("trace_id");
                    response.onNext(traceId != null ? traceId : "");
                    response.onCompleted();
                }))
                .build();
        TracingGrpcServerInterceptor tracing = serverInterceptor.getIfAvailable();
        try {
            server = ServerBuilder.forPort(port)
                    .directExecutor()   // a synchronous executor: the MDC set by the interceptor is visible to the handler
                    .addService(tracing != null ? ServerInterceptors.intercept(service, tracing) : service)
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("gRPC server failed to start on " + port, e);
        }
    }

    @Override
    public void stop() {
        Server s = server;
        if (s != null) {
            s.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    static final class Utf8 implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
