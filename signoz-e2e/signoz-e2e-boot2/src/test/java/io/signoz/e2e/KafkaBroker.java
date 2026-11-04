package io.signoz.e2e;

import org.springframework.kafka.test.EmbeddedKafkaBroker;

/** An in-process Kafka broker (spring-kafka-test 2.x, with ZooKeeper). */
final class KafkaBroker implements AutoCloseable {

    private final EmbeddedKafkaBroker broker;

    private KafkaBroker(String... topics) {
        broker = new EmbeddedKafkaBroker(1, true, 1, topics);
        broker.afterPropertiesSet();
    }

    static KafkaBroker start(String... topics) {
        return new KafkaBroker(topics);
    }

    String bootstrapServers() {
        return broker.getBrokersAsString();
    }

    @Override
    public void close() {
        broker.destroy();
    }
}
