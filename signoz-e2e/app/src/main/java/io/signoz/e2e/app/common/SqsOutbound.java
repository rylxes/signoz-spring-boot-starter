package io.signoz.e2e.app.common;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import io.signoz.springboot.sqs.TracingSqsRequestHandler;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Sends to the peer's queue with an AWS SDK v1 client carrying the starter's request handler. */
@Component
@ConditionalOnProperty("e2e.sqs.enabled")
public class SqsOutbound implements DisposableBean {

    private final AmazonSQS sqs;
    private final String outQueueUrl;

    public SqsOutbound(ObjectProvider<TracingSqsRequestHandler> tracing,
                       @Value("${e2e.sqs.endpoint}") String endpoint,
                       @Value("${e2e.sqs.out-queue-url}") String outQueueUrl) {
        AmazonSQSClientBuilder builder = AmazonSQSClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("e2e", "e2e")));
        TracingSqsRequestHandler handler = tracing.getIfAvailable();
        if (handler != null) {
            builder.withRequestHandlers(handler);   // the one line the tracing guide asks for
        }
        this.sqs = builder.build();
        this.outQueueUrl = outQueueUrl;
    }

    public void send(String payload) {
        sqs.sendMessage(outQueueUrl, payload);
    }

    @Override
    public void destroy() {
        sqs.shutdown();
    }
}
