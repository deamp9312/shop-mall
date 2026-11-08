package com.example.shop.event;

import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.TestInfrastructure;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** REQ-IF-002 verification 1) six topics + DLTs with 3 partitions, 3) one order's events share a partition. */
class KafkaTopicsTest extends IntegrationTestSupport {

    private static final long ORDER_A = 9_401L;
    private static final long ORDER_B = 9_402L;

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from outbox_events where message_key in (?, ?)", String.valueOf(ORDER_A),
                String.valueOf(ORDER_B));
    }

    @Test
    void topicsAndDeadLetterTopicsExistWithThreePartitions() throws Exception {
        List<String> expected = new ArrayList<>();
        EventTopics.ALL.forEach(topic -> {
            expected.add(topic);
            expected.add(topic + EventTopics.DLT_SUFFIX);
        });
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestInfrastructure.kafkaBootstrapServers()))) {
            Map<String, TopicDescription> topics = admin.describeTopics(expected).allTopicNames().get();

            assertThat(topics).hasSize(12);
            topics.values().forEach(topic -> assertThat(topic.partitions()).hasSize(EventTopics.PARTITIONS));
        }
    }

    @Test
    void eventsOfOneOrderAreKeyedByOrderIdAndLandInOnePartition() {
        for (int i = 0; i < 3; i++) {
            transaction.executeWithoutResult(status -> {
                outbox.record(new PaymentCancelledEvent(ORDER_A, 1L));
                outbox.record(new PaymentCancelledEvent(ORDER_B, 2L));
            });
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "select count(*) from outbox_events where message_key in (?, ?) and status = 'SENT'", Integer.class,
                String.valueOf(ORDER_A), String.valueOf(ORDER_B)) == 6);

        List<ConsumerRecord<String, String>> records =
                KafkaTestClient.readByKey(EventTopics.PAYMENT_CANCELLED, String.valueOf(ORDER_A));
        assertThat(records).hasSize(3);
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.get(0).partition());
    }
}
