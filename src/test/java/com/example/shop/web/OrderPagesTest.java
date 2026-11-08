package com.example.shop.web;

import com.example.shop.common.TraceIdFilter;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.order.Order;
import com.example.shop.order.OrderStatus;
import com.example.shop.order.OrderStatusHistory;
import com.example.shop.order.OrderSummary;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.OrderFlow;
import com.example.shop.support.OrderFlow.PlacedOrder;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * REQ-F-012 (order list, detail, timeline with cause events, 404 for another member's order) and the mock payment
 * screen of REQ-F-008 2), 5) (fixed notice, owner only, buttons disabled after the deadline).
 */
class OrderPagesTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_520L;
    private static final long BOOK = 9_521L;
    private static final long PEN = 9_522L;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_521L, "pages-test@shop.local", "주문자", MemberRole.USER, null);
    private static final MemberPrincipal OTHER_MEMBER =
            new MemberPrincipal(9_522L, "pages-other@shop.local", "다른 회원", MemberRole.USER, null);

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
        products.create(BOOK, 15_000, 30);
        products.create(PEN, 1_000, 30);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        flow.deleteOrdersOf(MEMBER.getMemberId(), OTHER_MEMBER.getMemberId());
        products.deleteAll();
    }

    @Test
    void timelineShowsPendingPaidCancelledWithTheirCauses() throws Exception {
        PlacedOrder order = flow.placeAndPay(MEMBER, Map.of(BOOK, 1, PEN, 2));
        flow.cancel(MEMBER, order.id());
        flow.awaitNotifications(order.id(), 2);

        Order detail = (Order) mvc.perform(get("/orders/" + order.id()).with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(view().name("order/detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PAYMENT_COMPLETED")))
                .andExpect(model().attributeExists("payment", "notifications"))
                .andReturn().getModelAndView().getModel().get("order");

        assertThat(detail.getStatusHistory()).extracting(OrderStatusHistory::getToStatus)
                .containsExactly(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.CANCELLED);
        OrderStatusHistory paid = detail.getStatusHistory().get(1);
        assertThat(paid.getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(paid.getEventId()).isEqualTo(flow.outboxEventId(order.id(), "shop.payment.completed"));
        assertThat(detail.getStatusHistory().get(2).getReason()).isEqualTo("USER_CANCEL");
        assertThat(detail.getItems()).hasSize(2);
    }

    @Test
    void listShowsTheMembersOrdersNewestFirstWithRepresentativeProduct() throws Exception {
        long first = flow.place(MEMBER, Map.of(BOOK, 1)).id();
        long second = flow.place(MEMBER, Map.of(BOOK, 1, PEN, 1)).id();
        flow.place(OTHER_MEMBER, Map.of(PEN, 1));

        @SuppressWarnings("unchecked")
        Page<OrderSummary> page = (Page<OrderSummary>) mvc.perform(get("/orders").with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(view().name("order/list"))
                .andReturn().getModelAndView().getModel().get("orders");

        assertThat(page.getSize()).isEqualTo(10);
        assertThat(page.getContent()).extracting(OrderSummary::orderId).containsExactly(second, first);
        assertThat(page.getContent().get(0).otherItemCount()).isEqualTo(1);
        assertThat(page.getContent().get(0).totalAmount()).isEqualTo(16_000);
    }

    @Test
    void anotherMembersOrderIsNotFound() throws Exception {
        long orderId = flow.place(MEMBER, Map.of(BOOK, 1)).id();

        mvc.perform(get("/orders/" + orderId).with(user(OTHER_MEMBER)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
        mvc.perform(get("/payments/" + orderId).with(user(OTHER_MEMBER)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/orders")).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void paymentScreenShowsTheMockNoticeAndDisablesPaymentAfterTheDeadline() throws Exception {
        long orderId = flow.placeAndAwaitPayment(MEMBER, Map.of(BOOK, 1)).id();

        mvc.perform(get("/payments/" + orderId).with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/pay"))
                .andExpect(model().attribute("payable", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "포트폴리오용 모의 결제 — 실제 결제되지 않음")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("cardNumber"))));

        jdbc.update("update orders set payment_expires_at = ? where id = ?", LocalDateTime.now().minusSeconds(1),
                orderId);
        mvc.perform(get("/payments/" + orderId).with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("payable", false));
        flow.awaitOrderStatus(orderId, "CANCELLED");
    }
}
