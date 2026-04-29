package io.signoz.springboot.sqs;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TracingSqsRequestHandlerTest {

    private Scope activateTestSpan() {
        SpanContext spanContext = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        return Context.current().with(Span.wrap(spanContext)).makeCurrent();
    }

    @Test
    void sendMessageGetsTraceparentAttributeWhenSpanActive() {
        try (Scope ignored = activateTestSpan()) {
            TracingSqsRequestHandler handler = new TracingSqsRequestHandler();
            SendMessageRequest req = new SendMessageRequest("queue-url", "body");

            handler.beforeExecution(req);

            MessageAttributeValue attr = req.getMessageAttributes().get(SqsTraceContext.TRACEPARENT_ATTRIBUTE);
            assertThat(attr).isNotNull();
            assertThat(attr.getDataType()).isEqualTo("String");
            assertThat(attr.getStringValue())
                    .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        }
    }

    @Test
    void sendMessageBatchInjectsIntoEachEntry() {
        try (Scope ignored = activateTestSpan()) {
            TracingSqsRequestHandler handler = new TracingSqsRequestHandler();
            SendMessageBatchRequest req = new SendMessageBatchRequest("queue-url",
                    Arrays.asList(
                            new SendMessageBatchRequestEntry("id-1", "body-1"),
                            new SendMessageBatchRequestEntry("id-2", "body-2")));

            handler.beforeExecution(req);

            for (SendMessageBatchRequestEntry entry : req.getEntries()) {
                MessageAttributeValue attr = entry.getMessageAttributes().get(SqsTraceContext.TRACEPARENT_ATTRIBUTE);
                assertThat(attr).isNotNull();
                assertThat(attr.getStringValue()).startsWith("00-0af7651916cd43dd8448eb211c80319c-");
            }
        }
    }

    @Test
    void sendMessageWithoutSpanOrMdcLeavesAttributesUntouched() {
        TracingSqsRequestHandler handler = new TracingSqsRequestHandler();
        SendMessageRequest req = new SendMessageRequest("queue-url", "body");

        handler.beforeExecution(req);

        // No span, no MDC → no injection
        assertThat(req.getMessageAttributes()).isNullOrEmpty();
    }

    @Test
    void unrelatedRequestsArePassedThroughUntouched() {
        TracingSqsRequestHandler handler = new TracingSqsRequestHandler();
        SendMessageRequest req = new SendMessageRequest("queue-url", "body");

        // beforeExecution returns the same request reference
        assertThat(handler.beforeExecution(req)).isSameAs(req);
    }
}
