package io.signoz.e2e.support;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;

/** An in-process SQS-compatible server (ElasticMQ). */
public final class SqsServer implements AutoCloseable {

    public static final String REGION = "us-east-1";
    public static final String ACCESS_KEY = "e2e";
    public static final String SECRET_KEY = "e2e";

    private final SQSRestServer server;
    private final int port;

    private SqsServer(int port) {
        // ElasticMQ runs on Pekko, whose SLF4J logger must answer within 5s by default; on a busy
        // machine running two services alongside, it sometimes does not.
        System.setProperty("pekko.logger-startup-timeout", "60s");
        this.port = port;
        this.server = SQSRestServerBuilder.withPort(port).withInterface("127.0.0.1").start();
        server.waitUntilStarted();
    }

    public static SqsServer start() {
        return new SqsServer(Ports.free());
    }

    public String endpoint() {
        return "http://127.0.0.1:" + port;
    }

    /** Creates the queue and returns its URL. */
    public String createQueue(String name) {
        AmazonSQS sqs = client();
        try {
            return sqs.createQueue(name).getQueueUrl();
        } finally {
            sqs.shutdown();
        }
    }

    /** The URL of an existing queue, as the server reports it. */
    public String queueUrl(String name) {
        AmazonSQS sqs = client();
        try {
            return sqs.getQueueUrl(name).getQueueUrl();
        } finally {
            sqs.shutdown();
        }
    }

    private AmazonSQS client() {
        return AmazonSQSClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint(), REGION))
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY)))
                .build();
    }

    @Override
    public void close() {
        server.stopAndWait();
    }
}
