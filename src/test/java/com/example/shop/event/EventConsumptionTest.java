package com.example.shop.event;

import com.example.shop.common.InvalidStateTransitionException;
import com.example.shop.common.MdcKeys;
import com.example.shop.order.OrderStatus;
import com.example.shop.stock.StockReservationService;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.TestInfrastructure;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * REQ-NF-002 verification 1) duplicate delivery handled once, 2) forced failure -> 4 attempts -> 1 DLT record and the
 * next record still handled, 3) non-retryable failures go to the DLT at once; DLT records are stored (4);
 * REQ-NF-003: the consumer MDC carries the traceId from the Kafka header.
 *
 * REQ-NF-002 검증 1) 중복 전달은 한 번만 처리, 2) 강제 실패 -> 4번 시도 -> DLT 레코드 1개, 다음 레코드는 여전히 처리,
 * 3) 재시도 불가 실패는 바로 DLT로 가고, DLT 레코드는 저장된다(4).
 * REQ-NF-003: 컨슈머 MDC에 Kafka 헤더의 traceId가 들어 있다.
 */
class EventConsumptionTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_430L;
    private static final long PRODUCT_ID = 9_431L;
    private static final long ORDER_ID = 9_431L;
    private static final String TEST_TOPIC = "shop.test.forced-failure";
    private static final String TEST_GROUP = "shop-test-forced-failure";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotentEventProcessor processor;

    @Autowired
    private StockReservationService stock;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<String, String> factory;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private TestProducts products;

    @BeforeEach
    void setUp() {
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
    }

    @AfterEach
    void cleanUp() {
        products.deleteAll();
    }

    @Test
    void sameEventDeliveredTwiceIsHandledOnce() throws Exception {
        products.create(PRODUCT_ID, 1_000, 10);
        transaction.executeWithoutResult(status -> stock.reserve(ORDER_ID, Map.of(PRODUCT_ID, 3)));
        assertThat(products.available(PRODUCT_ID)).isEqualTo("7");
        EventEnvelope<PaymentFailedEvent> event = EventEnvelope.wrap(new PaymentFailedEvent(ORDER_ID, 1L), "trace-dup");
        String json = objectMapper.writeValueAsString(event);

        KafkaTestClient.send(EventTopics.PAYMENT_FAILED, String.valueOf(ORDER_ID), json, Map.of());
        KafkaTestClient.send(EventTopics.PAYMENT_FAILED, String.valueOf(ORDER_ID), json, Map.of());
        KafkaTestClient.awaitConsumed(ConsumerGroups.STOCK, EventTopics.PAYMENT_FAILED);

        assertThat(products.available(PRODUCT_ID)).isEqualTo("10");
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer_group = ? and event_id = ?",
                Integer.class, ConsumerGroups.STOCK, event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from stock_reservations where order_id = ?", String.class,
                ORDER_ID)).isEqualTo("RELEASED");
    }

    @Test
    void failingRecordIsTriedFourTimesThenDeadLetteredAndTheNextRecordIsHandled() throws Exception {
        createTopicWithDeadLetterTopic();
        Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        List<String> handled = new CopyOnWriteArrayList<>();
        List<String> traceIds = new CopyOnWriteArrayList<>();
        ConcurrentMessageListenerContainer<String, String> container = testContainer(record -> {
            attempts.computeIfAbsent(record.key(), key -> new AtomicInteger()).incrementAndGet();
            traceIds.add(String.valueOf(MDC.get(MdcKeys.TRACE_ID)));
            processor.process(TEST_GROUP, record, PaymentCancelRequestedEvent.class, event -> {
                switch (event.payload().reason()) {
                    case "fail" -> throw new IllegalStateException("forced failure");
                    case "invalid-transition" -> throw new InvalidStateTransitionException("Order", event.aggregateId(),
                            OrderStatus.CANCELLED, OrderStatus.PAID);
                    default -> handled.add(record.key());
                }
            });
        });
        try {
            send("1", "fail", "trace-forced-1");
            send("2", "ok", "trace-forced-2");
            KafkaTestClient.send(TEST_TOPIC, "3", "not-json", Map.of());
            send("4", "invalid-transition", "trace-forced-4");
            send("5", "ok", "trace-forced-5");

            await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains("5"));
            await().atMost(Duration.ofSeconds(30)).until(() -> deadLettersOfTestTopic().size() == 3);
        } finally {
            container.stop();
        }

        assertThat(attempts.get("1")).hasValue(4);
        assertThat(attempts.get("3")).hasValue(1);
        assertThat(attempts.get("4")).hasValue(1);
        assertThat(handled).containsExactly("2", "5");
        assertThat(deadLettersOfTestTopic()).extracting(ConsumerRecord::key).containsExactlyInAnyOrder("1", "3", "4");
        assertThat(traceIds).contains("trace-forced-1", "trace-forced-2");
    }

    @Test
    void malformedRecordOnARealTopicIsStoredAsDeadLetter() {
        String payload = "not-json-" + UUID.randomUUID();

        KafkaTestClient.send(EventTopics.PAYMENT_FAILED, String.valueOf(ORDER_ID), payload, Map.of());

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "select count(*) from dead_letter_events where payload = ?", Integer.class, payload) >= 1);
        Map<String, Object> row = jdbc.queryForMap(
                "select original_topic, error_message from dead_letter_events where payload = ? limit 1", payload);
        assertThat(row.get("original_topic")).isEqualTo(EventTopics.PAYMENT_FAILED);
        assertThat((String) row.get("error_message")).contains("malformed event");
    }

    private void send(String key, String reason, String traceId) throws Exception {
        EventEnvelope<PaymentCancelRequestedEvent> event =
                EventEnvelope.wrap(new PaymentCancelRequestedEvent(Long.parseLong(key), reason), traceId);
        KafkaTestClient.send(TEST_TOPIC, key, objectMapper.writeValueAsString(event),
                Map.of(EventHeaders.TRACE_ID, traceId));
    }

    private List<ConsumerRecord<String, String>> deadLettersOfTestTopic() {
        return KafkaTestClient.readAll(TEST_TOPIC + EventTopics.DLT_SUFFIX);
    }

    /**
     * A container built by the application's factory, so it gets the same error handler and interceptor.
     *
     * 애플리케이션의 팩토리로 만든 컨테이너라서 같은 오류 핸들러와 인터셉터를 받는다.
     */
    private ConcurrentMessageListenerContainer<String, String> testContainer(MessageListener<String, String> listener) {
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(TEST_TOPIC);
        container.getContainerProperties().setGroupId(TEST_GROUP);
        container.getContainerProperties().setMessageListener(listener);
        container.start();
        return container;
    }

    private static void createTopicWithDeadLetterTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestInfrastructure.kafkaBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TEST_TOPIC, 1, (short) 1),
                    new NewTopic(TEST_TOPIC + EventTopics.DLT_SUFFIX, 1, (short) 1))).all().get();
        }
    }
}
