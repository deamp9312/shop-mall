package com.example.shop.stock;

import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.DomainEvent;
import com.example.shop.event.EventEnvelope;
import com.example.shop.event.EventTopics;
import com.example.shop.event.OrderCancelledEvent;
import com.example.shop.event.PaymentCompletedEvent;
import com.example.shop.event.PaymentFailedEvent;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-F-007: all-or-nothing Lua reservation, confirm on payment.completed, release on payment.failed and
 * order.cancelled (also after confirm, REQ-F-011 4). Payment events are published directly (payment module: step 4).
 *
 * REQ-F-007: 전부 아니면 전무인 Lua 선점, payment.completed에 확정, payment.failed와 order.cancelled에 해제 (확정
 * 후에도, REQ-F-011 4). 결제 이벤트는 직접 발행한다 (결제 모듈: 4단계).
 */
class StockReservationFlowTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_440L;
    private static final long MUG = 9_441L;
    private static final long CUP = 9_442L;
    private static final long ORDER_ID = 9_441L;

    @Autowired
    private StockReservationService stock;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private TestProducts products;

    @BeforeEach
    void setUp() {
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(MUG, 1_000, 10);
        products.create(CUP, 2_000, 2);
    }

    @AfterEach
    void cleanUp() {
        products.deleteAll();
    }

    @Test
    void oneShortProductReservesNothing() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                stock.reserve(ORDER_ID, Map.of(MUG, 3, CUP, 5))))
                .isInstanceOf(OutOfStockException.class)
                .extracting(e -> ((OutOfStockException) e).getProductIds()).isEqualTo(List.of(CUP));

        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.available(CUP)).isEqualTo("2");
        assertThat(reservationStatuses()).isEmpty();
    }

    @Test
    void rollbackAfterTheReservationGivesTheStockBack() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            stock.reserve(ORDER_ID, Map.of(MUG, 3, CUP, 2));
            assertThat(products.available(MUG)).isEqualTo("7");
            throw new IllegalStateException("order save failed");
        })).hasMessage("order save failed");

        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.available(CUP)).isEqualTo("2");
        assertThat(reservationStatuses()).isEmpty();
    }

    @Test
    void paymentCompletedConfirmsAndLowersDatabaseStockOnly() throws Exception {
        reserveMugAndCup();

        publish(EventTopics.PAYMENT_COMPLETED, new PaymentCompletedEvent(ORDER_ID, 1L, 7_000L, LocalDateTime.now()));

        assertThat(reservationStatuses()).containsOnly("CONFIRMED");
        assertThat(products.databaseStock(MUG)).isEqualTo(7);
        assertThat(products.databaseStock(CUP)).isEqualTo(0);
        assertThat(products.available(MUG)).isEqualTo("7");
    }

    @Test
    void paymentFailedReturnsStockToTheValueBeforeTheOrder() throws Exception {
        reserveMugAndCup();

        publish(EventTopics.PAYMENT_FAILED, new PaymentFailedEvent(ORDER_ID, 1L));

        assertThat(reservationStatuses()).containsOnly("RELEASED");
        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.available(CUP)).isEqualTo("2");
        assertThat(products.databaseStock(MUG)).isEqualTo(10);
    }

    @Test
    void cancellingAPaidOrderRestoresDatabaseAndRedisStock() throws Exception {
        reserveMugAndCup();
        publish(EventTopics.PAYMENT_COMPLETED, new PaymentCompletedEvent(ORDER_ID, 1L, 7_000L, LocalDateTime.now()));

        publish(EventTopics.ORDER_CANCELLED, new OrderCancelledEvent(ORDER_ID, 1L, "USER_CANCEL"));

        assertThat(reservationStatuses()).containsOnly("RELEASED");
        assertThat(products.databaseStock(MUG)).isEqualTo(10);
        assertThat(products.databaseStock(CUP)).isEqualTo(2);
        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.available(CUP)).isEqualTo("2");
    }

    @Test
    void lateConfirmAfterReleaseChangesNothing() throws Exception {
        reserveMugAndCup();
        publish(EventTopics.ORDER_CANCELLED, new OrderCancelledEvent(ORDER_ID, 1L, "PAYMENT_TIMEOUT"));

        publish(EventTopics.PAYMENT_COMPLETED, new PaymentCompletedEvent(ORDER_ID, 1L, 7_000L, LocalDateTime.now()));

        assertThat(reservationStatuses()).containsOnly("RELEASED");
        assertThat(products.databaseStock(MUG)).isEqualTo(10);
        assertThat(products.available(MUG)).isEqualTo("10");
    }

    private void reserveMugAndCup() {
        transaction.executeWithoutResult(status -> stock.reserve(ORDER_ID, Map.of(MUG, 3, CUP, 2)));
        assertThat(products.available(MUG)).isEqualTo("7");
        assertThat(products.available(CUP)).isEqualTo("0");
    }

    private void publish(String topic, DomainEvent event) throws Exception {
        String json = objectMapper.writeValueAsString(EventEnvelope.wrap(event, "trace-stock"));
        KafkaTestClient.send(topic, String.valueOf(event.orderId()), json, Map.of());
        KafkaTestClient.awaitConsumed(ConsumerGroups.STOCK, topic);
    }

    private List<String> reservationStatuses() {
        return jdbc.queryForList("select status from stock_reservations where order_id = ?", String.class, ORDER_ID);
    }
}
