package io.signoz.e2e;

import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** An in-process Kafka broker (spring-kafka-test 3.x, KRaft). */
final class KafkaBroker implements AutoCloseable {

    private final EmbeddedKafkaKraftBroker broker;

    private KafkaBroker(String... topics) {
        broker = new EmbeddedKafkaKraftBroker(1, 1, topics);
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
