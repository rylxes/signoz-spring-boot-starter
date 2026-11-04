package io.signoz.e2e.app.sqs;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.signoz.e2e.app.common.Observations;
import io.signoz.springboot.annotation.SigNozLog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.Collections;

/** Spring Cloud AWS 3 listener; the starter's aspect extracts the trace context from the message. */
@SigNozLog
@Component
@ConditionalOnProperty("e2e.sqs.enabled")
public class SqsInbound {

    private final Observations observations;

    public SqsInbound(Observations observations) {
        this.observations = observations;
    }

    @SqsListener("${e2e.sqs.in-queue}")
    public void onMessage(Message<String> message) {
        log.info("sqs received {}", message.getPayload());
        observations.record("sqs", Collections.singletonMap("payload", message.getPayload()));
    }
}
