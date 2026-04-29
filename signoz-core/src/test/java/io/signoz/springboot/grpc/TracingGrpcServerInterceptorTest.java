package io.signoz.springboot.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracingGrpcServerInterceptorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverInterceptorPopulatesMdcFromInboundMetadata() {
        TracingGrpcServerInterceptor interceptor = new TracingGrpcServerInterceptor();
        Metadata headers = new Metadata();
        headers.put(TracingGrpcClientInterceptor.TRACEPARENT_KEY,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        headers.put(TracingGrpcClientInterceptor.REQUEST_ID_KEY, "req-abc");

        AtomicBoolean mdcSeen = new AtomicBoolean(false);

        ServerCallHandler handler = (call, h) -> {
            // Inside the handler the MDC should be populated.
            mdcSeen.set("0af7651916cd43dd8448eb211c80319c".equals(MDC.get("traceId"))
                    && "b7ad6b7169203331".equals(MDC.get("spanId"))
                    && "req-abc".equals(MDC.get("requestId")));
            return mock(ServerCall.Listener.class);
        };

        ServerCall.Listener<?> listener = interceptor.interceptCall(
                mock(ServerCall.class), headers, handler);

        assertThat(mdcSeen.get()).isTrue();

        // After the listener completes, MDC must be cleared.
        listener.onComplete();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverInterceptorClearsMdcOnCancel() {
        TracingGrpcServerInterceptor interceptor = new TracingGrpcServerInterceptor();
        Metadata headers = new Metadata();
        headers.put(TracingGrpcClientInterceptor.TRACEPARENT_KEY,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        ServerCallHandler handler = (call, h) -> mock(ServerCall.Listener.class);
        ServerCall.Listener<?> listener = interceptor.interceptCall(
                mock(ServerCall.class), headers, handler);

        listener.onCancel();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverInterceptorClearsMdcOnCallClose() {
        TracingGrpcServerInterceptor interceptor = new TracingGrpcServerInterceptor();
        Metadata headers = new Metadata();
        headers.put(TracingGrpcClientInterceptor.TRACEPARENT_KEY,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        // Capture the wrapped ServerCall so we can call close() on it.
        ServerCall realCall = mock(ServerCall.class);
        AtomicBoolean wasClosed = new AtomicBoolean(false);
        ServerCallHandler handler = (call, h) -> {
            // call here is the wrapped one; record close behavior by invoking it.
            try {
                call.close(Status.OK, new Metadata());
                wasClosed.set(true);
            } catch (Exception ignore) {
                // mock ServerCall close is a no-op
            }
            return mock(ServerCall.Listener.class);
        };
        when(realCall.getMethodDescriptor()).thenReturn(null);

        interceptor.interceptCall(realCall, headers, handler);

        assertThat(wasClosed.get()).isTrue();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverInterceptorWithNoTraceparentDoesNotPopulateMdc() {
        TracingGrpcServerInterceptor interceptor = new TracingGrpcServerInterceptor();
        Metadata headers = new Metadata();

        ServerCallHandler handler = (call, h) -> mock(ServerCall.Listener.class);
        interceptor.interceptCall(mock(ServerCall.class), headers, handler);

        assertThat(MDC.get("traceId")).isNull();
    }
}
