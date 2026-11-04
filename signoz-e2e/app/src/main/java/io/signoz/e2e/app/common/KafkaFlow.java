package io.signoz.e2e.app.common;

import io.signoz.springboot.annotation.SigNozLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Sends to the peer's topic and consumes this service's topic. The interceptors come from configuration. */
@SigNozLog
@Component
@ConditionalOnProperty("e2e.kafka.enabled")
public class KafkaFlow {

    private final KafkaTemplate<String, String> template;
    private final Observations observations;
    private final String outTopic;

    public KafkaFlow(KafkaTemplate<String, String> template, Observations observations,
                     @Value("${e2e.kafka.out-topic}") String outTopic) {
        this.template = template;
        this.observations = observations;
        this.outTopic = outTopic;
    }

    public void send(String payload) throws Exception {
        template.send(outTopic, payload).get(15, TimeUnit.SECONDS);
    }

    @KafkaListener(topics = "${e2e.kafka.in-topic}", groupId = "${e2e.name}")
    public void onMessage(String payload) {
        log.info("kafka received {}", payload);
        observations.record("kafka", Collections.singletonMap("payload", payload));
    }
}
