package com.example.shop.event;

import com.example.shop.common.MdcKeys;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** REQ-DA-002: outbox rows are sent with key and trace headers, give up after 5 failures, and SENT rows expire. */
class OutboxRelayTest extends IntegrationTestSupport {

    private static final long ORDER_ID = 9_411L;
    private static final long REJECTED_ORDER_ID = 9_412L;

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from outbox_events where message_key in (?, ?)", String.valueOf(ORDER_ID),
                String.valueOf(REJECTED_ORDER_ID));
        MDC.remove(MdcKeys.TRACE_ID);
    }

    @Test
    void recordedEventIsPublishedWithTraceHeaderAndMarkedSent() {
        MDC.put(MdcKeys.TRACE_ID, "trace-outbox-1");
        transaction.executeWithoutResult(status -> outbox.record(new PaymentCancelRequestedEvent(ORDER_ID, "test")));

        await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(statusOf(ORDER_ID)));
        List<ConsumerRecord<String, String>> records =
                KafkaTestClient.readByKey(EventTopics.PAYMENT_CANCEL_REQUESTED, String.valueOf(ORDER_ID));
        assertThat(records).hasSize(1);
        assertThat(new String(records.get(0).headers().lastHeader(EventHeaders.TRACE_ID).value(),
                StandardCharsets.UTF_8)).isEqualTo("trace-outbox-1");
        assertThat(records.get(0).value()).contains("trace-outbox-1");
        assertThat(jdbc.queryForObject("select sent_at is not null from outbox_events where message_key = ?",
                Boolean.class, String.valueOf(ORDER_ID))).isTrue();
    }

    @Test
    void recordingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outbox.record(new PaymentCancelRequestedEvent(ORDER_ID, "test")))
                .hasMessageContaining("mandatory");
    }

    @Test
    void anEventKafkaRejectsBecomesFailedAfterFiveAttempts() {
        // An illegal topic name is a permanent error (InvalidTopicException), not a broker outage.
        jdbc.update("insert into outbox_events (event_id, topic, message_key, payload, status, retry_count, created_at)"
                + " values (?, 'shop invalid topic!', ?, '{\"traceId\": \"t\", \"eventType\": \"X\"}', 'PENDING', 0,"
                + " now(6))", UUID.randomUUID().toString(), String.valueOf(REJECTED_ORDER_ID));

        await().atMost(Duration.ofSeconds(30)).until(() -> "FAILED".equals(statusOf(REJECTED_ORDER_ID)));
        assertThat(jdbc.queryForObject("select retry_count from outbox_events where message_key = ?", Integer.class,
                String.valueOf(REJECTED_ORDER_ID))).isEqualTo(OutboxEvent.MAX_ATTEMPTS);
    }

    @Test
    void sentEventsOlderThanSevenDaysAreDeleted() {
        insertSent("old-event-0000000000000000000000001", 8);
        insertSent("new-event-0000000000000000000000001", 6);

        relay.deleteOldSentEvents();

        assertThat(jdbc.queryForList("select event_id from outbox_events where message_key = ?", String.class,
                String.valueOf(ORDER_ID))).containsExactly("new-event-0000000000000000000000001");
    }

    private void insertSent(String eventId, int daysAgo) {
        jdbc.update("insert into outbox_events (event_id, topic, message_key, payload, status, retry_count, created_at,"
                + " sent_at) values (?, ?, ?, '{}', 'SENT', 0, now(6), now(6) - interval ? day)",
                eventId, EventTopics.ORDER_CREATED, String.valueOf(ORDER_ID), daysAgo);
    }

    private String statusOf(long orderId) {
        return jdbc.queryForList("select status from outbox_events where message_key = ?", String.class,
                String.valueOf(orderId)).stream().findFirst().orElse(null);
    }
}
