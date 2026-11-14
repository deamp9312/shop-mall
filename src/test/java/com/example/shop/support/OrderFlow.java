package com.example.shop.support;

import com.example.shop.common.TraceIdFilter;
import com.example.shop.member.MemberPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the order -> mock payment -> result flow through the HTTP API on the empty-schema context and reads the
 * resulting rows. {@link #deleteOrdersOf} removes everything the flow wrote for the given members; call it before
 * {@link TestProducts#deleteAll()}.
 *
 * 빈 스키마 컨텍스트에서 HTTP API로 주문 -> 모의 결제 -> 결과 흐름을 진행하고 결과 행을 읽는다. {@link
 * #deleteOrdersOf}는 주어진 회원에 대해 이 흐름이 쓴 모든 것을 지운다. {@link TestProducts#deleteAll()}보다 먼저
 * 호출한다.
 */
public final class OrderFlow {

    public static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(60);
    /**
     * Shipping info every order of this flow sends; personal data that must never reach a log (REQ-SE-003 3).
     *
     * 이 흐름의 모든 주문이 보내는 배송 정보. 로그에 절대 남으면 안 되는 개인정보다 (REQ-SE-003 3).
     */
    public static final String RECEIVER_NAME = "홍길동";
    public static final String RECEIVER_PHONE = "010-1234-5678";
    public static final String ADDRESS_LINE = "서울시 어딘가 1";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OrderFlow(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/orders; the traceId is the X-Trace-Id of that request.
     *
     * POST /api/orders. traceId는 그 요청의 X-Trace-Id다.
     */
    public PlacedOrder place(MemberPrincipal member, Map<Long, Integer> quantities) throws Exception {
        MvcResult result = attempt(member, quantities).andExpect(status().isOk()).andReturn();
        return placedOrder(result);
    }

    /**
     * POST /api/orders without expecting success, for tests where some orders must be refused.
     *
     * 성공을 기대하지 않는 POST /api/orders. 일부 주문이 거절되어야 하는 테스트용.
     */
    public ResultActions attempt(MemberPrincipal member, Map<Long, Integer> quantities) throws Exception {
        String items = quantities.entrySet().stream()
                .map(line -> "{\"productId\":" + line.getKey() + ",\"qty\":" + line.getValue() + "}")
                .collect(Collectors.joining(","));
        return mvc.perform(post("/api/orders").with(user(member)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + items + "],\"fromCart\":false,\"address\":{"
                        + "\"receiverName\":\"" + RECEIVER_NAME + "\",\"receiverPhone\":\"" + RECEIVER_PHONE
                        + "\",\"line\":\"" + ADDRESS_LINE + "\"}}"));
    }

    public PlacedOrder placedOrder(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new PlacedOrder(body.get("orderId").asLong(), result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER));
    }

    /**
     * Places the order and waits until the payment module has created the READY payment from order.created.
     *
     * 주문하고, 결제 모듈이 order.created로 READY 결제를 만들 때까지 기다린다.
     */
    public PlacedOrder placeAndAwaitPayment(MemberPrincipal member, Map<Long, Integer> quantities) throws Exception {
        PlacedOrder order = place(member, quantities);
        awaitPaymentStatus(order.id(), "READY");
        return order;
    }

    public ResultActions approve(MemberPrincipal member, long orderId, String result) throws Exception {
        return mvc.perform(post("/mock-pg/approve").with(user(member)).with(csrf())
                .header("Idempotency-Key", String.valueOf(orderId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":" + orderId + ",\"result\":\"" + result + "\",\"method\":\"CARD\"}"));
    }

    public ResultActions cancel(MemberPrincipal member, long orderId) throws Exception {
        return mvc.perform(post("/api/orders/" + orderId + "/cancel").with(user(member)).with(csrf()));
    }

    public PlacedOrder placeAndPay(MemberPrincipal member, Map<Long, Integer> quantities) throws Exception {
        PlacedOrder order = placeAndAwaitPayment(member, quantities);
        approve(member, order.id(), "SUCCESS").andExpect(status().isOk());
        awaitOrderStatus(order.id(), "PAID");
        return order;
    }

    public void awaitOrderStatus(long orderId, String expected) {
        await().atMost(ASYNC_TIMEOUT).until(() -> expected.equals(orderStatus(orderId)));
    }

    public void awaitPaymentStatus(long orderId, String expected) {
        await().atMost(ASYNC_TIMEOUT).until(() -> expected.equals(paymentStatus(orderId)));
    }

    public void awaitReservationStatus(long orderId, String expected) {
        await().atMost(ASYNC_TIMEOUT).until(() -> jdbc.queryForList(
                "select status from stock_reservations where order_id = ?", String.class, orderId)
                .stream().allMatch(expected::equals));
    }

    public void awaitNotifications(long orderId, int expected) {
        await().atMost(ASYNC_TIMEOUT).until(() -> notificationTypes(orderId).size() == expected);
    }

    public String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    public String paymentStatus(long orderId) {
        List<String> statuses = jdbc.queryForList("select status from payments where order_id = ?", String.class,
                orderId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    public List<String> notificationTypes(long orderId) {
        return jdbc.queryForList("select type from notification_logs where order_id = ? order by id", String.class,
                orderId);
    }

    public int outboxCount(long orderId, String topic) {
        return jdbc.queryForObject("select count(*) from outbox_events where message_key = ? and topic = ?",
                Integer.class, String.valueOf(orderId), topic);
    }

    public String outboxEventId(long orderId, String topic) {
        return jdbc.queryForObject("select event_id from outbox_events where message_key = ? and topic = ?",
                String.class, String.valueOf(orderId), topic);
    }

    public void deleteOrdersOf(long... memberIds) {
        for (long memberId : memberIds) {
            List<Long> orderIds = jdbc.queryForList("select id from orders where member_id = ?", Long.class, memberId);
            orderIds.forEach(this::deleteOrder);
        }
    }

    private void deleteOrder(long orderId) {
        String key = String.valueOf(orderId);
        jdbc.update("delete p from processed_events p join outbox_events o on o.event_id = p.event_id"
                + " where o.message_key = ?", key);
        jdbc.update("delete from outbox_events where message_key = ?", key);
        jdbc.update("delete from notification_logs where order_id = ?", orderId);
        jdbc.update("delete from payments where order_id = ?", orderId);
        jdbc.update("delete from stock_reservations where order_id = ?", orderId);
        jdbc.update("delete from order_status_history where order_id = ?", orderId);
        jdbc.update("delete from order_items where order_id = ?", orderId);
        jdbc.update("delete from orders where id = ?", orderId);
    }

    public record PlacedOrder(long id, String traceId) {
    }
}
