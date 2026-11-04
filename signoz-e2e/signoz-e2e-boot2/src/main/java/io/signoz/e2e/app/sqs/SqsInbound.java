package io.signoz.e2e.app.sqs;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;
import io.awspring.cloud.messaging.config.annotation.EnableSqs;
import io.awspring.cloud.messaging.listener.SqsMessageDeletionPolicy;
import io.awspring.cloud.messaging.listener.annotation.SqsListener;
import io.signoz.e2e.app.common.Observations;
import io.signoz.springboot.annotation.SigNozLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.Collections;

/** Spring Cloud AWS 2 listener; the starter's aspect extracts the trace context from the message. */
public final class SqsInbound {

    private SqsInbound() {
    }

    @Configuration
    @EnableSqs
    @ConditionalOnProperty("e2e.sqs.enabled")
    public static class Config {

        @Bean
        @Primary
        public AmazonSQSAsync amazonSqs(@Value("${e2e.sqs.endpoint}") String endpoint) {
            return AmazonSQSAsyncClientBuilder.standard()
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
                    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("e2e", "e2e")))
                    .build();
        }
    }

    @SigNozLog
    @Component
    @ConditionalOnProperty("e2e.sqs.enabled")
    public static class Listener {

        private final Observations observations;

        public Listener(Observations observations) {
            this.observations = observations;
        }

        @SqsListener(value = "${e2e.sqs.in-queue}", deletionPolicy = SqsMessageDeletionPolicy.ON_SUCCESS)
        public void onMessage(Message<String> message) {
            log.info("sqs received {}", message.getPayload());
            observations.record("sqs", Collections.singletonMap("payload", message.getPayload()));
        }
    }
}
