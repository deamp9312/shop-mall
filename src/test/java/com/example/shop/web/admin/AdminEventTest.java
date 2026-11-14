package com.example.shop.web.admin;

import com.example.shop.common.TraceIdFilter;
import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.EventTopics;
import com.example.shop.event.EventTraceQuery.EventTraceEntry;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * REQ-F-014: 1) one order's published and consumed events in time order, 2) failure switch -> retries -> DLT ->
 * [재처리] handled once by the failed group only; REQ-IF-001 6) dead letter retry API, normal and error cases.
 *
 * REQ-F-014: 1) 주문 한 건의 발행·소비 이벤트가 시간순으로 보인다, 2) 장애 스위치 -> 재시도 -> DLT -> [재처리]는 실패한
 * 그룹만 한 번 처리한다. REQ-IF-001 6) DLT 재처리 API의 정상·오류 경우.
 */
class AdminEventTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_510L;
    private static final long PRODUCT_ID = 9_511L;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_511L, "event-test@shop.local", "이벤트", MemberRole.USER, null);
    private static final MemberPrincipal ADMIN =
            new MemberPrincipal(9_512L, "event-admin@shop.local", "관리자", MemberRole.ADMIN, null);
    private static final String MALFORMED_PAYLOAD = "not-an-envelope-9511";

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
        products.create(PRODUCT_ID, 2_000, 10);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
    }

    @AfterEach
    void cleanUp() throws Exception {
        armSwitch(ConsumerGroups.NOTIFICATION, 0);
        jdbc.update("delete from dead_letter_events where payload = ?", MALFORMED_PAYLOAD);
        flow.deleteOrdersOf(MEMBER.getMemberId());
        products.deleteAll();
    }

    @Test
    void eventScreenShowsPublishedAndConsumedEventsOfOneOrderInTimeOrder() throws Exception {
        PlacedOrder order = flow.placeAndPay(MEMBER, Map.of(PRODUCT_ID, 1));
        flow.cancel(MEMBER, order.id());
        flow.awaitNotifications(order.id(), 2);

        @SuppressWarnings("unchecked")
        List<EventTraceEntry> trace = (List<EventTraceEntry>) mvc.perform(get("/admin/events")
                        .param("orderId", String.valueOf(order.id())).with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/events"))
                .andReturn().getModelAndView().getModel().get("trace");

        assertThat(trace).isSortedAccordingTo(Comparator.comparing(EventTraceEntry::at));
        assertThat(trace).filteredOn(e -> e.kind() == EventTraceEntry.Kind.PUBLISHED)
                .extracting(EventTraceEntry::topic)
                .containsExactlyInAnyOrder(EventTopics.ORDER_CREATED, EventTopics.PAYMENT_COMPLETED,
                        EventTopics.ORDER_CANCELLED, EventTopics.PAYMENT_CANCELLED);
        assertThat(trace).filteredOn(e -> e.kind() == EventTraceEntry.Kind.CONSUMED)
                .extracting(EventTraceEntry::consumerGroup)
                .contains(ConsumerGroups.PAYMENT, ConsumerGroups.ORDER, ConsumerGroups.STOCK,
                        ConsumerGroups.NOTIFICATION);
        String createdEventId = flow.outboxEventId(order.id(), EventTopics.ORDER_CREATED);
        EventTraceEntry published = trace.stream().filter(e -> e.eventId().equals(createdEventId)
                && e.kind() == EventTraceEntry.Kind.PUBLISHED).findFirst().orElseThrow();
        EventTraceEntry consumed = trace.stream().filter(e -> e.eventId().equals(createdEventId)
                && e.kind() == EventTraceEntry.Kind.CONSUMED).findFirst().orElseThrow();
        assertThat(trace.indexOf(published)).isLessThan(trace.indexOf(consumed));
        assertThat(published.result()).isEqualTo("SENT");
    }

    @Test
    void failureSwitchSendsTheEventToTheDltAndTheRetryIsHandledOnlyByTheFailedGroup() throws Exception {
        armSwitch(ConsumerGroups.NOTIFICATION, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$['shop-notification']").value(1));

        PlacedOrder order = flow.placeAndPay(MEMBER, Map.of(PRODUCT_ID, 2));
        String completedEventId = flow.outboxEventId(order.id(), EventTopics.PAYMENT_COMPLETED);
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> deadLetterIdOf(completedEventId) != null);
        flow.awaitReservationStatus(order.id(), "CONFIRMED");
        assertThat(flow.notificationTypes(order.id())).isEmpty();
        assertThat(jdbc.queryForObject("select error_message from dead_letter_events where id = ?", String.class,
                deadLetterIdOf(completedEventId))).contains("forced failure");

        Long deadLetterId = deadLetterIdOf(completedEventId);
        retry(deadLetterId).andExpect(status().isOk()).andExpect(jsonPath("$.requeued").value(true));

        flow.awaitNotifications(order.id(), 1);
        assertThat(flow.notificationTypes(order.id())).containsExactly("PAYMENT_COMPLETED");
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> processedCount(completedEventId) == 3);
        assertThat(products.databaseStock(PRODUCT_ID)).isEqualTo(8);
        assertThat(flow.orderStatus(order.id())).isEqualTo("PAID");
        retry(deadLetterId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEAD_LETTER_ALREADY_RETRIED"));
    }

    @Test
    void retryAndSwitchErrors() throws Exception {
        retry(Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEAD_LETTER_NOT_FOUND"));
        jdbc.update("insert into dead_letter_events (original_topic, payload, error_message, created_at)"
                + " values (?, ?, 'malformed', now(6))", EventTopics.PAYMENT_FAILED, MALFORMED_PAYLOAD);
        Long malformedId = jdbc.queryForObject("select id from dead_letter_events where payload = ?", Long.class,
                MALFORMED_PAYLOAD);
        retry(malformedId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEAD_LETTER_NOT_RETRYABLE"));

        mvc.perform(post("/api/admin/dlt/" + malformedId + "/retry").with(user(MEMBER)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        armSwitch("shop-unknown", 1).andExpect(status().isBadRequest());
        armSwitch(ConsumerGroups.ORDER, -1).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/events").with(user(MEMBER))).andExpect(status().isForbidden());
    }

    private ResultActions retry(long deadLetterId) throws Exception {
        return mvc.perform(post("/api/admin/dlt/" + deadLetterId + "/retry").with(user(ADMIN)).with(csrf()));
    }

    private ResultActions armSwitch(String group, int count) throws Exception {
        return mvc.perform(post("/api/admin/failure-switch").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consumerGroup\":\"" + group + "\",\"count\":" + count + "}"));
    }

    private Long deadLetterIdOf(String eventId) {
        List<Long> ids = jdbc.queryForList("select id from dead_letter_events where payload like ? order by id",
                Long.class, "%" + eventId + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private int processedCount(String eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, eventId);
    }
}
