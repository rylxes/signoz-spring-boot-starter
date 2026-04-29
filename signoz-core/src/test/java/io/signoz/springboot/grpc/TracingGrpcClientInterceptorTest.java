package io.signoz.springboot.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracingGrpcClientInterceptorTest {

    private Scope activateTestSpan() {
        SpanContext spanContext = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        return Context.current().with(Span.wrap(spanContext)).makeCurrent();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clientInterceptorWritesTraceparentToMetadata() {
        try (Scope ignored = activateTestSpan()) {
            TracingGrpcClientInterceptor interceptor = new TracingGrpcClientInterceptor();

            // Mock the downstream ClientCall and capture the Metadata it receives.
            ClientCall mockCall = mock(ClientCall.class);
            AtomicReference<Metadata> captured = new AtomicReference<>();
            org.mockito.Mockito.doAnswer(invocation -> {
                captured.set(invocation.getArgument(1));
                return null;
            }).when(mockCall).start(any(), any(Metadata.class));

            Channel channel = mock(Channel.class);
            when(channel.newCall(any(), any(CallOptions.class))).thenReturn(mockCall);

            MethodDescriptor methodDescriptor = mock(MethodDescriptor.class);
            when(methodDescriptor.getFullMethodName()).thenReturn("test.Service/Method");

            ClientCall wrapped = interceptor.interceptCall(methodDescriptor, CallOptions.DEFAULT, channel);
            wrapped.start(mock(ClientCall.Listener.class), new Metadata());

            assertThat(captured.get()).isNotNull();
            String traceparent = captured.get().get(TracingGrpcClientInterceptor.TRACEPARENT_KEY);
            assertThat(traceparent)
                    .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clientInterceptorSkipsHeaderWhenNoIdentity() {
        // No active span, no MDC → traceparent is not set
        TracingGrpcClientInterceptor interceptor = new TracingGrpcClientInterceptor();

        ClientCall mockCall = mock(ClientCall.class);
        AtomicReference<Metadata> captured = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return null;
        }).when(mockCall).start(any(), any(Metadata.class));

        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any(CallOptions.class))).thenReturn(mockCall);

        ClientCall wrapped = interceptor.interceptCall(mock(MethodDescriptor.class),
                CallOptions.DEFAULT, channel);
        wrapped.start(mock(ClientCall.Listener.class), new Metadata());

        assertThat(captured.get().get(TracingGrpcClientInterceptor.TRACEPARENT_KEY)).isNull();
    }
}
