package com.example.shop.event;

import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review finding Medium-3 (2026-11-06): a later event of an order must not overtake an earlier one that keeps failing
 * or has FAILED, and the admin can put a FAILED row back to PENDING (REQ-DA-002, REQ-F-014, ADMIN + CSRF).
 *
 * 리뷰 지적 Medium-3 (2026-11-06): 한 주문의 뒤 이벤트가 계속 실패 중이거나 FAILED인 앞 이벤트를 앞지르면 안 되고,
 * 관리자는 FAILED 행을 PENDING으로 되돌릴 수 있다 (REQ-DA-002, REQ-F-014, ADMIN + CSRF).
 */
class OutboxFailedEventTest extends IntegrationTestSupport {

    private static final long FAILING_ORDER_ID = 9_421L;
    private static final long FAILED_ORDER_ID = 9_422L;
    private static final MemberPrincipal ADMIN =
            new MemberPrincipal(9_421L, "outbox-admin@shop.local", "관리자", MemberRole.ADMIN, null);
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_422L, "outbox-user@shop.local", "회원", MemberRole.USER, null);

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from outbox_events where message_key in (?, ?)", String.valueOf(FAILING_ORDER_ID),
                String.valueOf(FAILED_ORDER_ID));
    }

    @Test
    void laterEventOfTheSameOrderWaitsWhileAnEarlierOneFailsAndAfterItHasFailed() {
        transaction.executeWithoutResult(status -> {
            // An illegal topic name is a permanent error: the row is retried five times and then FAILED.
            // 잘못된 토픽 이름은 영구 오류다. 행은 다섯 번 재시도된 뒤 FAILED가 된다.
            insertPending(UUID.randomUUID().toString(), "shop invalid topic!", FAILING_ORDER_ID);
            outbox.record(new PaymentCancelRequestedEvent(FAILING_ORDER_ID, "test"));
        });
        long later = idOf(FAILING_ORDER_ID, EventTopics.PAYMENT_CANCEL_REQUESTED);

        await().atMost(Duration.ofSeconds(30)).until(() -> "FAILED".equals(statusOfRow(
                idOf(FAILING_ORDER_ID, "shop invalid topic!"))));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> "PENDING".equals(statusOfRow(later)));
    }

    @Test
    void requeuedFailedEventIsSentBeforeTheEventsItHeldBack() throws Exception {
        String failedEventId = transaction.execute(status -> {
            outbox.record(new PaymentCancelRequestedEvent(FAILED_ORDER_ID, "first"));
            jdbc.update("update outbox_events set status = 'FAILED', retry_count = 5 where message_key = ?",
                    String.valueOf(FAILED_ORDER_ID));
            outbox.record(new PaymentCancelRequestedEvent(FAILED_ORDER_ID, "second"));
            return jdbc.queryForObject("select event_id from outbox_events where message_key = ? and status ="
                    + " 'FAILED'", String.class, String.valueOf(FAILED_ORDER_ID));
        });
        long failedId = jdbc.queryForObject("select id from outbox_events where event_id = ?", Long.class,
                failedEventId);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> pendingCount(FAILED_ORDER_ID) == 1);

        mvc.perform(get("/admin/events").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("heldBackOutboxEvents", 1L));

        requeue(failedId, ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.requeued").value(true));

        await().atMost(Duration.ofSeconds(30)).until(() -> pendingCount(FAILED_ORDER_ID) == 0
                && sentCount(FAILED_ORDER_ID) == 2);
        List<ConsumerRecord<String, String>> records =
                KafkaTestClient.readByKey(EventTopics.PAYMENT_CANCEL_REQUESTED, String.valueOf(FAILED_ORDER_ID));
        assertThat(records).hasSize(2);
        assertThat(records.get(0).value()).contains(failedEventId);
        requeue(failedId, ADMIN).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_FAILED"));
    }

    @Test
    void requeueErrors() throws Exception {
        requeue(Long.MAX_VALUE, ADMIN).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_FOUND"));
        requeue(Long.MAX_VALUE, MEMBER).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/outbox/" + Long.MAX_VALUE + "/requeue").with(user(ADMIN)))
                .andExpect(status().isForbidden());
    }

    private ResultActions requeue(long id, MemberPrincipal principal) throws Exception {
        return mvc.perform(post("/api/admin/outbox/" + id + "/requeue").with(user(principal)).with(csrf()));
    }

    private void insertPending(String eventId, String topic, long orderId) {
        jdbc.update("insert into outbox_events (event_id, topic, message_key, payload, status, retry_count, created_at)"
                + " values (?, ?, ?, '{\"traceId\": \"t\", \"eventType\": \"X\"}', 'PENDING', 0, now(6))",
                eventId, topic, String.valueOf(orderId));
    }

    private long idOf(long orderId, String topic) {
        return jdbc.queryForObject("select id from outbox_events where message_key = ? and topic = ?", Long.class,
                String.valueOf(orderId), topic);
    }

    private String statusOfRow(long id) {
        return jdbc.queryForObject("select status from outbox_events where id = ?", String.class, id);
    }

    private int pendingCount(long orderId) {
        return countWithStatus(orderId, "PENDING");
    }

    private int sentCount(long orderId) {
        return countWithStatus(orderId, "SENT");
    }

    private int countWithStatus(long orderId, String status) {
        return jdbc.queryForObject("select count(*) from outbox_events where message_key = ? and status = ?",
                Integer.class, String.valueOf(orderId), status);
    }
}
