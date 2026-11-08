package com.example.shop.event;

import com.example.shop.support.ApplicationProcess;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * REQ-NF-002 verification 3): the application is killed (SIGKILL) while the stock consumer is in the middle of
 * payment.completed - processed_events inserted, reservation confirmed, but not committed. After a restart the record
 * is delivered again (its offset was never committed) and the DB stock goes down exactly once.
 * <p>
 * The consumer is held in place by a row lock this test takes on the product, the row it updates last. The
 * application runs as separate processes on a broker and a database of their own, so its consumer groups do not share
 * partitions with the in-JVM test context.
 */
class ConsumerCrashRecoveryTest {

    private static final String DATABASE = "shop_crash";
    private static final int REDIS_DATABASE = 2;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final long CATEGORY_ID = 9_600L;
    private static final long PRODUCT = 9_601L;
    private static final long MEMBER_ID = 9_601L;
    private static final int STOCK = 10;
    private static final int QUANTITY = 3;

    private static KafkaContainer kafka;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startBroker() {
        kafka = TestInfrastructure.startKafka();
        dataSource = TestInfrastructure.dataSource(DATABASE);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopBroker() {
        kafka.stop();
    }

    @Test
    void recordInterruptedByAKillIsHandledOnceAfterTheRestart() throws Exception {
        ApplicationProcess first = ApplicationProcess.start("crash-before", applicationProperties());
        long orderId;
        String eventId;
        try (Connection productLock = dataSource.getConnection()) {
            orderId = insertReservedOrder();
            lockProduct(productLock);
            eventId = sendPaymentCompleted(orderId);
            await().atMost(TIMEOUT).until(() -> transactionsWaitingForALock() > 0);

            first.kill();
            productLock.rollback();
        } finally {
            first.kill();
        }
        await().atMost(TIMEOUT).until(() -> openTransactions() == 0);
        assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
        assertThat(databaseStock()).isEqualTo(STOCK);
        assertThat(stockConsumerRecords(eventId)).isZero();

        ApplicationProcess restarted = ApplicationProcess.start("crash-after", applicationProperties());
        try {
            await().atMost(TIMEOUT).until(() -> "CONFIRMED".equals(reservationStatus(orderId)));
            assertThat(databaseStock()).isEqualTo(STOCK - QUANTITY);
            assertThat(stockConsumerRecords(eventId)).isEqualTo(1);
            await().atMost(TIMEOUT).until(() -> paidTransitions(orderId) == 1);
        } finally {
            restarted.kill();
        }
    }

    /**
     * A dead member keeps its partitions until its session times out; 6 s (the broker minimum) instead of 45 s lets
     * the restarted application take over quickly.
     */
    private static Map<String, String> applicationProperties() {
        Map<String, String> properties = new HashMap<>(TestInfrastructure.connectionProperties(DATABASE,
                REDIS_DATABASE, kafka.getBootstrapServers()));
        properties.put("spring.kafka.consumer.properties.session.timeout.ms", "6000");
        properties.put("spring.kafka.consumer.properties.heartbeat.interval.ms", "1000");
        return properties;
    }

    /** An order waiting for payment with its stock reserved, as OrderService leaves it. */
    private static long insertReservedOrder() {
        jdbc.update("insert into categories (id, name) values (?, 'crash-test')", CATEGORY_ID);
        jdbc.update("insert into products (id, category_id, name, description, price, stock, image_url, created_at,"
                + " updated_at) values (?, ?, 'crash-product', 'd', 1000, ?, '/x.svg', now(6), now(6))",
                PRODUCT, CATEGORY_ID, STOCK);
        jdbc.update("insert into orders (member_id, status, total_amount, receiver_name, receiver_phone,"
                + " shipping_address, payment_expires_at, created_at, updated_at, version)"
                + " values (?, 'PAYMENT_PENDING', ?, 'r', '010', 'a', ?, now(6), now(6), 0)",
                MEMBER_ID, 1000L * QUANTITY, LocalDateTime.now().plusMinutes(15));
        long orderId = jdbc.queryForObject("select id from orders where member_id = ?", Long.class, MEMBER_ID);
        jdbc.update("insert into order_items (order_id, product_id, product_name, unit_price, quantity)"
                + " values (?, ?, 'crash-product', 1000, ?)", orderId, PRODUCT, QUANTITY);
        jdbc.update("insert into stock_reservations (order_id, product_id, quantity, status, created_at, updated_at)"
                + " values (?, ?, ?, 'RESERVED', now(6), now(6))", orderId, PRODUCT, QUANTITY);
        return orderId;
    }

    private static void lockProduct(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        String sql = "select stock from products where id = ? for update";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setLong(1, PRODUCT);
            try (ResultSet ignored = select.executeQuery()) {
                // the row lock is held until rollback
            }
        }
    }

    private static String sendPaymentCompleted(long orderId) throws Exception {
        EventEnvelope<PaymentCompletedEvent> envelope = EventEnvelope.wrap(
                new PaymentCompletedEvent(orderId, 1L, 1000L * QUANTITY, LocalDateTime.now()), "trace-crash");
        String json = Jackson2ObjectMapperBuilder.json().build().writeValueAsString(envelope);
        KafkaTestClient.send(kafka.getBootstrapServers(), EventTopics.PAYMENT_COMPLETED, String.valueOf(orderId), json,
                Map.of());
        return envelope.eventId();
    }

    private static int transactionsWaitingForALock() {
        return jdbc.queryForObject("select count(*) from information_schema.innodb_trx where trx_state = 'LOCK WAIT'",
                Integer.class);
    }

    private static int openTransactions() {
        return jdbc.queryForObject("select count(*) from information_schema.innodb_trx"
                + " where trx_mysql_thread_id <> connection_id()", Integer.class);
    }

    private static String reservationStatus(long orderId) {
        return jdbc.queryForObject("select status from stock_reservations where order_id = ?", String.class, orderId);
    }

    private static int databaseStock() {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, PRODUCT);
    }

    private static int stockConsumerRecords(String eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where consumer_group = ? and event_id = ?",
                Integer.class, ConsumerGroups.STOCK, eventId);
    }

    private static int paidTransitions(long orderId) {
        return jdbc.queryForObject("select count(*) from order_status_history where order_id = ?"
                + " and to_status = 'PAID'", Integer.class, orderId);
    }
}
