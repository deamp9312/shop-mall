package com.example.shop.payment;

import com.example.shop.common.IdempotencyScope;
import com.example.shop.common.RedisKeys;
import com.example.shop.common.TraceIdFilter;
import com.example.shop.event.EventEnvelope;
import com.example.shop.event.EventHeaders;
import com.example.shop.event.EventTopics;
import com.example.shop.event.PaymentCompletedEvent;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.OrderFlow;
import com.example.shop.support.OrderFlow.PlacedOrder;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-F-008 (mock payment), REQ-F-009 (result consumers), REQ-F-011 (cancel and compensation), REQ-SE-002 2), 3), 4)
 * (approval idempotency) and REQ-IF-001 4), 5) (cancel and approve API, normal and error cases).
 *
 * REQ-F-008 (모의 결제), REQ-F-009 (결과 컨슈머), REQ-F-011 (취소와 보상), REQ-SE-002 2), 3), 4) (승인 멱등성),
 * REQ-IF-001 4), 5) (취소·승인 API, 정상과 오류 경우).
 */
class PaymentFlowTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_480L;
    private static final long MUG = 9_481L;      // 10,000 won, stock 10
    // 10,000원, 재고 10
    private static final long PLATE = 9_482L;    //  4,000 won, stock 10
    // 4,000원, 재고 10
    private static final int INITIAL_STOCK = 10;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_481L, "pay-test@shop.local", "결제자", MemberRole.USER, null);
    private static final MemberPrincipal OTHER_MEMBER =
            new MemberPrincipal(9_482L, "pay-other@shop.local", "다른 회원", MemberRole.USER, null);

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

    private MockMvc mvc;
    private TestProducts products;
    private OrderFlow flow;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter).apply(springSecurity()).build();
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(MUG, 10_000, INITIAL_STOCK);
        products.create(PLATE, 4_000, INITIAL_STOCK);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        flow.deleteOrdersOf(MEMBER.getMemberId(), OTHER_MEMBER.getMemberId());
        products.deleteAll();
    }

    @Test
    void successfulPaymentPaysTheOrderConfirmsStockAndNotifiesOnce() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 2));

        flow.approve(MEMBER, order.id(), "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"));

        flow.awaitOrderStatus(order.id(), "PAID");
        flow.awaitReservationStatus(order.id(), "CONFIRMED");
        flow.awaitNotifications(order.id(), 1);
        assertThat(flow.paymentStatus(order.id())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select paid_at from orders where id = ?", LocalDateTime.class, order.id()))
                .isNotNull();
        assertThat(products.databaseStock(MUG)).isEqualTo(8);
        assertThat(products.available(MUG)).isEqualTo("8");
        assertThat(flow.notificationTypes(order.id())).containsExactly("PAYMENT_COMPLETED");
        assertThat(jdbc.queryForObject("select method from payments where order_id = ?", String.class, order.id()))
                .isEqualTo("CARD");
    }

    @Test
    void failedPaymentFailsTheOrderAndRestoresStock() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 3));
        assertThat(products.available(MUG)).isEqualTo("7");

        flow.approve(MEMBER, order.id(), "FAIL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

        flow.awaitOrderStatus(order.id(), "PAYMENT_FAILED");
        flow.awaitReservationStatus(order.id(), "RELEASED");
        flow.awaitNotifications(order.id(), 1);
        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.databaseStock(MUG)).isEqualTo(INITIAL_STOCK);
        assertThat(flow.notificationTypes(order.id())).containsExactly("PAYMENT_FAILED");
    }

    @Test
    void delayedAnswerKeepsTheOrderPendingAndThenPays() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 1));

        flow.approve(MEMBER, order.id(), "DELAY")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentStatus").value("READY"));

        assertThat(flow.orderStatus(order.id())).isEqualTo("PAYMENT_PENDING");
        flow.approve(MEMBER, order.id(), "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
        flow.awaitOrderStatus(order.id(), "PAID");
        assertThat(flow.outboxCount(order.id(), EventTopics.PAYMENT_COMPLETED)).isEqualTo(1);
    }

    @Test
    void twoConcurrentApprovalsPublishOnePaymentCompleted() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 1));
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> clicks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            clicks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return flow.approve(MEMBER, order.id(), "SUCCESS").andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        start.countDown();
        List<Integer> statuses = clicks.stream().map(CompletableFuture::join).toList();

        assertThat(statuses).contains(200).allMatch(code -> code == 200 || code == 409);
        flow.awaitOrderStatus(order.id(), "PAID");
        assertThat(flow.outboxCount(order.id(), EventTopics.PAYMENT_COMPLETED)).isEqualTo(1);
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() ->
                KafkaTestClient.readByKey(EventTopics.PAYMENT_COMPLETED, String.valueOf(order.id())).size() == 1);

        // Second layer: the Redis key is gone, but the conditional UPDATE (READY only) still approves nothing twice.
        // 두 번째 방어선: Redis 키가 사라져도 조건부 UPDATE(READY만)가 두 번 승인하지 않게 막는다.
        redis.delete(RedisKeys.idempotency(IdempotencyScope.PAYMENT, String.valueOf(order.id())));
        flow.approve(MEMBER, order.id(), "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThat(flow.outboxCount(order.id(), EventTopics.PAYMENT_COMPLETED)).isEqualTo(1);
    }

    @Test
    void cancelBeforePaymentRestoresStockAndCancelsThePayment() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 4));

        flow.cancel(MEMBER, order.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        flow.awaitPaymentStatus(order.id(), "CANCELLED");
        flow.awaitReservationStatus(order.id(), "RELEASED");
        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.databaseStock(MUG)).isEqualTo(INITIAL_STOCK);
        assertThat(jdbc.queryForObject("select cancel_reason from orders where id = ?", String.class, order.id()))
                .isEqualTo("USER_CANCEL");
        assertThat(flow.outboxCount(order.id(), EventTopics.PAYMENT_CANCELLED)).isZero();
    }

    @Test
    void cancelAfterPaymentRestoresDatabaseAndRedisStockAndCancelsThePayment() throws Exception {
        PlacedOrder order = flow.placeAndPay(MEMBER, Map.of(MUG, 2, PLATE, 1));
        flow.awaitReservationStatus(order.id(), "CONFIRMED");
        assertThat(products.databaseStock(MUG)).isEqualTo(8);

        flow.cancel(MEMBER, order.id()).andExpect(status().isOk());

        flow.awaitPaymentStatus(order.id(), "CANCELLED");
        flow.awaitReservationStatus(order.id(), "RELEASED");
        flow.awaitNotifications(order.id(), 2);
        assertThat(products.databaseStock(MUG)).isEqualTo(INITIAL_STOCK);
        assertThat(products.databaseStock(PLATE)).isEqualTo(INITIAL_STOCK);
        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.available(PLATE)).isEqualTo("10");
        assertThat(flow.outboxCount(order.id(), EventTopics.PAYMENT_CANCELLED)).isEqualTo(1);
        assertThat(flow.notificationTypes(order.id())).containsExactly("PAYMENT_COMPLETED", "PAYMENT_CANCELLED");

        flow.cancel(MEMBER, order.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
    }

    @Test
    void paymentCompletedForACancelledOrderRequestsThePaymentCancellation() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 1));
        flow.cancel(MEMBER, order.id()).andExpect(status().isOk());
        flow.awaitPaymentStatus(order.id(), "CANCELLED");
        flow.awaitReservationStatus(order.id(), "RELEASED");

        EventEnvelope<PaymentCompletedEvent> late = EventEnvelope.wrap(
                new PaymentCompletedEvent(order.id(), 1L, 10_000, LocalDateTime.now()), "trace-late-payment");
        KafkaTestClient.send(EventTopics.PAYMENT_COMPLETED, String.valueOf(order.id()),
                objectMapper.writeValueAsString(late), Map.of(EventHeaders.TRACE_ID, "trace-late-payment"));

        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() ->
                KafkaTestClient.readByKey(EventTopics.PAYMENT_CANCEL_REQUESTED, String.valueOf(order.id())).size() == 1);
        assertThat(objectMapper.readTree(KafkaTestClient.readByKey(EventTopics.PAYMENT_CANCEL_REQUESTED,
                String.valueOf(order.id())).get(0).value()).get("traceId").asText()).isEqualTo("trace-late-payment");
        assertThat(flow.orderStatus(order.id())).isEqualTo("CANCELLED");
        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.databaseStock(MUG)).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void approvalErrors() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 1));

        flow.approve(OTHER_MEMBER, order.id(), "SUCCESS")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(post("/mock-pg/approve").with(user(MEMBER)).with(csrf()).header("Idempotency-Key", "12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + order.id() + ",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/mock-pg/approve").with(user(MEMBER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + order.id() + ",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
        flow.approve(MEMBER, order.id(), "MAYBE").andExpect(status().isBadRequest());
        mvc.perform(post("/mock-pg/approve").with(csrf()).header("Idempotency-Key", String.valueOf(order.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + order.id() + ",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());

        jdbc.update("update payments set expires_at = ? where order_id = ?", LocalDateTime.now().minusSeconds(1),
                order.id());
        flow.approve(MEMBER, order.id(), "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"));

        jdbc.update("delete from payments where order_id = ?", order.id());
        flow.approve(MEMBER, order.id(), "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_READY"));
        assertThat(flow.outboxCount(order.id(), EventTopics.PAYMENT_COMPLETED)).isZero();
    }

    @Test
    void cancelErrors() throws Exception {
        PlacedOrder order = flow.placeAndAwaitPayment(MEMBER, Map.of(MUG, 1));

        flow.cancel(OTHER_MEMBER, order.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        flow.approve(MEMBER, order.id(), "FAIL").andExpect(status().isOk());
        flow.awaitOrderStatus(order.id(), "PAYMENT_FAILED");
        flow.cancel(MEMBER, order.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        assertThat(flow.outboxCount(order.id(), EventTopics.ORDER_CANCELLED)).isZero();
    }
}
