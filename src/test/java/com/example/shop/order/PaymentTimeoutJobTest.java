package com.example.shop.order;

import com.example.shop.common.RedisKeys;
import com.example.shop.common.TraceIdFilter;
import com.example.shop.event.EventTopics;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.OrderFlow;
import com.example.shop.support.OrderFlow.PlacedOrder;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * REQ-F-010: overdue PAYMENT_PENDING orders are cancelled with PAYMENT_TIMEOUT, stock comes back and the READY payment is
 * cancelled; concurrent runs (two instances) still publish one order.cancelled per order. The test profile runs the job
 * every second; a test moves an order's deadline into the past instead of waiting 15 minutes.
 *
 * REQ-F-010: 기한이 지난 PAYMENT_PENDING 주문은 PAYMENT_TIMEOUT으로 취소되고, 재고가 돌아오며, READY 결제가 취소된다.
 * 동시 실행(인스턴스 두 개)이어도 주문마다 order.cancelled는 하나만 발행된다. test 프로필은 작업을 매초 실행하며,
 * 테스트는 15분을 기다리는 대신 주문 기한을 과거로 옮긴다.
 */
class PaymentTimeoutJobTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_500L;
    private static final long PRODUCT_ID = 9_501L;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_501L, "timeout-test@shop.local", "만료", MemberRole.USER, null);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TraceIdFilter traceIdFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private PaymentTimeoutJob job;

    @Autowired
    private OrderCancellationService cancellation;

    private TestProducts products;
    private OrderFlow flow;

    @BeforeEach
    void setUp() {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter)
                .apply(springSecurity()).build();
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(PRODUCT_ID, 1_000, 20);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(RedisKeys.lock(PaymentTimeoutJob.LOCK_NAME));
        flow.deleteOrdersOf(MEMBER.getMemberId());
        products.deleteAll();
    }

    @Test
    void overdueOrderIsCancelledAndItsStockAndPaymentAreReleased() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(PRODUCT_ID, 3));
        assertThat(products.available(PRODUCT_ID)).isEqualTo("17");

        makeOverdue(order.id());

        flow.awaitOrderStatus(order.id(), "CANCELLED");
        flow.awaitPaymentStatus(order.id(), "CANCELLED");
        flow.awaitReservationStatus(order.id(), "RELEASED");
        assertThat(products.available(PRODUCT_ID)).isEqualTo("20");
        assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, order.id()))
                .isEqualTo("PAYMENT_TIMEOUT");
        assertThat(flow.outboxCount(order.id(), EventTopics.ORDER_CANCELLED)).isEqualTo(1);
    }

    @Test
    void orderBeforeItsDeadlineIsLeftAlone() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(PRODUCT_ID, 1));

        job.cancelOverdueOrders();

        assertThat(flow.orderStatus(order.id())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void runsOfTwoInstancesAtOnceCancelEachOverdueOrderOnce() throws Exception {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            orderIds.add(flow.placeAndAwaitPayment(MEMBER, Map.of(PRODUCT_ID, 1)).id());
        }
        orderIds.forEach(this::makeOverdue);

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> instances = List.of(runAfter(start, job::cancelOverdueOrders),
                runAfter(start, job::cancelOverdueOrders));
        start.countDown();
        instances.forEach(CompletableFuture::join);

        orderIds.forEach(id -> flow.awaitOrderStatus(id, "CANCELLED"));
        orderIds.forEach(id -> assertThat(flow.outboxCount(id, EventTopics.ORDER_CANCELLED)).isEqualTo(1));
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> "20".equals(products.available(PRODUCT_ID)));
    }

    @Test
    void withoutTheLockTheOrderRowStillAllowsOneCancellation() throws Exception {
        long orderId = flow.placeAndAwaitPayment(MEMBER, Map.of(PRODUCT_ID, 1)).id();
        redis.opsForValue().set(RedisKeys.lock(PaymentTimeoutJob.LOCK_NAME), "another-instance");
        makeOverdue(orderId);
        LocalDateTime now = LocalDateTime.now();

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> calls = List.of(
                CompletableFuture.supplyAsync(() -> awaitThen(start, () -> cancellation.cancelIfPaymentOverdue(orderId, now))),
                CompletableFuture.supplyAsync(() -> awaitThen(start, () -> cancellation.cancelIfPaymentOverdue(orderId, now))));
        start.countDown();

        assertThat(calls.stream().map(CompletableFuture::join)).containsExactlyInAnyOrder(true, false);
        assertThat(flow.orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(flow.outboxCount(orderId, EventTopics.ORDER_CANCELLED)).isEqualTo(1);
    }

    @Test
    void runIsSkippedWhileAnotherInstanceHoldsTheLock() throws Exception {
        long orderId = flow.placeAndAwaitPayment(MEMBER, Map.of(PRODUCT_ID, 1)).id();
        redis.opsForValue().set(RedisKeys.lock(PaymentTimeoutJob.LOCK_NAME), "another-instance");
        makeOverdue(orderId);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> "PAYMENT_PENDING".equals(flow.orderStatus(orderId)));

        redis.delete(RedisKeys.lock(PaymentTimeoutJob.LOCK_NAME));
        flow.awaitOrderStatus(orderId, "CANCELLED");
    }

    private void makeOverdue(long orderId) {
        jdbc.update("update orders set payment_expires_at = ? where id = ?", LocalDateTime.now().minusMinutes(1),
                orderId);
    }

    private static CompletableFuture<Void> runAfter(CountDownLatch start, Runnable task) {
        return CompletableFuture.runAsync(() -> awaitThen(start, () -> {
            task.run();
            return null;
        }));
    }

    private static <T> T awaitThen(CountDownLatch start, java.util.function.Supplier<T> task) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return task.get();
    }
}
