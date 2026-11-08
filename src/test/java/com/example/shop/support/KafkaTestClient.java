package com.example.shop.support;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;

/** Raw Kafka access for tests against the broker of {@link IntegrationTestSupport}: send, read a topic, await lag 0. */
public final class KafkaTestClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private KafkaTestClient() {
    }

    public static void send(String topic, String key, String value, Map<String, String> headers) {
        send(TestInfrastructure.kafkaBootstrapServers(), topic, key, value, headers);
    }

    /** Sends to another broker than the one of {@link IntegrationTestSupport}. */
    public static void send(String bootstrapServers, String topic, String key, String value,
                            Map<String, String> headers) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            headers.forEach((name, headerValue) ->
                    record.headers().add(name, headerValue.getBytes(StandardCharsets.UTF_8)));
            producer.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every record currently in the topic, read without a consumer group. */
    public static List<ConsumerRecord<String, String>> readAll(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("could not read " + topic + " to the end");
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    public static List<ConsumerRecord<String, String>> readByKey(String topic, String key) {
        return readAll(topic).stream().filter(record -> key.equals(record.key())).toList();
    }

    /** Waits until the group has committed past every record of the topic, i.e. it has handled all of them. */
    public static void awaitConsumed(String group, String topic) {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers()))) {
            await().atMost(TIMEOUT).until(() -> lag(admin, group, topic) == 0);
        }
    }

    private static long lag(AdminClient admin, String group, String topic) throws Exception {
        List<TopicPartition> partitions = admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic)
                .partitions().stream().map(info -> new TopicPartition(topic, info.partition())).toList();
        Map<TopicPartition, Long> end = admin.listOffsets(partitions.stream()
                        .collect(Collectors.toMap(Function.identity(), p -> OffsetSpec.latest())))
                .all().get().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        Map<TopicPartition, OffsetAndMetadata> committed =
                admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
        long lag = 0;
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata offset = committed.get(partition);
            lag += end.get(partition) - (offset == null ? 0 : offset.offset());
        }
        return lag;
    }
}
