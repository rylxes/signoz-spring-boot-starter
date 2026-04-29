package io.signoz.springboot.sqs;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import io.signoz.springboot.tracing.TraceContextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS SDK v1 {@link RequestHandler2} that injects the W3C {@code traceparent}
 * (and forwards {@code X-Request-ID}) into the message attributes of every
 * outbound {@code SendMessage} / {@code SendMessageBatch} request.
 *
 * <p>Register on every {@code AmazonSQS}/{@code AmazonSQSAsync} client builder:
 * <pre>{@code
 * AmazonSQSAsyncClientBuilder.standard()
 *     .withRequestHandlers(tracingSqsRequestHandler)
 *     ...
 * }</pre>
 *
 * <p>The starter wires this automatically via a {@code BeanPostProcessor} when
 * the agent is absent.
 */
public class TracingSqsRequestHandler extends RequestHandler2 {

    private static final Logger logger = LoggerFactory.getLogger("SIGNOZ_SQS");

    @Override
    public AmazonWebServiceRequest beforeExecution(AmazonWebServiceRequest request) {
        if (request instanceof SendMessageRequest) {
            inject((SendMessageRequest) request);
        } else if (request instanceof SendMessageBatchRequest) {
            for (SendMessageBatchRequestEntry entry : ((SendMessageBatchRequest) request).getEntries()) {
                inject(entry);
            }
        }
        return request;
    }

    private void inject(SendMessageRequest req) {
        String traceparent = TraceContextCodec.currentTraceparent();
        if (traceparent == null) {
            return;
        }
        Map<String, MessageAttributeValue> attributes = req.getMessageAttributes();
        if (attributes == null) {
            attributes = new HashMap<>();
            req.setMessageAttributes(attributes);
        }
        putString(attributes, SqsTraceContext.TRACEPARENT_ATTRIBUTE, traceparent);
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isEmpty()) {
            putString(attributes, SqsTraceContext.REQUEST_ID_ATTRIBUTE, requestId);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("[SigNoz] Injected traceparent into SQS message for queue {}", req.getQueueUrl());
        }
    }

    private void inject(SendMessageBatchRequestEntry entry) {
        String traceparent = TraceContextCodec.currentTraceparent();
        if (traceparent == null) {
            return;
        }
        Map<String, MessageAttributeValue> attributes = entry.getMessageAttributes();
        if (attributes == null) {
            attributes = new HashMap<>();
            entry.setMessageAttributes(attributes);
        }
        putString(attributes, SqsTraceContext.TRACEPARENT_ATTRIBUTE, traceparent);
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isEmpty()) {
            putString(attributes, SqsTraceContext.REQUEST_ID_ATTRIBUTE, requestId);
        }
    }

    private static void putString(Map<String, MessageAttributeValue> attrs, String name, String value) {
        attrs.put(name, new MessageAttributeValue().withDataType("String").withStringValue(value));
    }
}
