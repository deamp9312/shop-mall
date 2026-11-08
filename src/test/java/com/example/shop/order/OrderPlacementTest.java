package com.example.shop.order;

import com.example.shop.common.IdempotencyScope;
import com.example.shop.common.RedisKeys;
import com.example.shop.common.ShopException;
import com.example.shop.common.TraceIdFilter;
import com.example.shop.event.EventTopics;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.TestInfrastructure;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-F-006 (order + outbox + order.created), REQ-SE-002 (idempotency key, Redis + DB), REQ-F-005 4) (cart),
 * REQ-SE-001 2) (owner check), REQ-DA-002 verification 1) (Kafka down while ordering) and a small concurrency check
 * (stock 10, 30 concurrent orders -> exactly 10; the full REQ-NF-001 test is step 5).
 */
class OrderPlacementTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_450L;
    private static final long TOWEL = 9_451L;   // 12,900 won
    private static final long SOAP = 9_452L;    //  8,900 won
    private static final long CANDLE = 9_453L;
    private static final long MEMBER_ID = 9_451L;
    private static final long OTHER_MEMBER_ID = 9_452L;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(MEMBER_ID, "order-test@shop.local", "주문자", MemberRole.USER, null);
    private static final MemberPrincipal OTHER_MEMBER =
            new MemberPrincipal(OTHER_MEMBER_ID, "other@shop.local", "다른 회원", MemberRole.USER, null);
    private static final ShippingInfo SHIPPING = new ShippingInfo("홍길동", "010-1234-5678", "서울시 어딘가 1");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TraceIdFilter traceIdFilter;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private MockMvc mvc;
    private TestProducts products;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter).apply(springSecurity()).build();
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(TOWEL, 12_900, 5);
        products.create(SOAP, 8_900, 1);
        products.create(CANDLE, 5_000, 3);
    }

    @AfterEach
    void cleanUp() {
        products.deleteAll();
        for (String table : List.of("order_status_history", "order_items")) {
            jdbc.update("delete from " + table + " where order_id in (select id from orders where member_id in (?, ?))",
                    MEMBER_ID, OTHER_MEMBER_ID);
        }
        jdbc.update("delete from orders where member_id in (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
        redis.delete(RedisKeys.cart(MEMBER_ID));
    }

    @Test
    void orderIsStoredWithOneOutboxRowAndPublishedOnceWithServerPrices() throws Exception {
        MvcResult result = placeOrder(UUID.randomUUID().toString(),
                "{\"items\":[{\"productId\":" + TOWEL + ",\"qty\":2,\"price\":1}],\"totalAmount\":1,"
                        + addressJson() + ",\"fromCart\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isNumber())
                .andReturn();
        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("orderId").asLong();
        String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("paymentUrl").asText())
                .isEqualTo("/payments/" + orderId);
        Map<String, Object> order = jdbc.queryForMap(
                "select status, total_amount, payment_expires_at from orders where member_id = ?", MEMBER_ID);
        assertThat(order.get("status")).isEqualTo("PAYMENT_PENDING");
        assertThat(order.get("total_amount")).isEqualTo(25_800L);
        assertThat(((LocalDateTime) order.get("payment_expires_at")))
                .isBetween(LocalDateTime.now().plusMinutes(14), LocalDateTime.now().plusMinutes(16));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where message_key = ? and topic = ?",
                Integer.class, String.valueOf(orderId), EventTopics.ORDER_CREATED)).isEqualTo(1);
        assertThat(products.available(TOWEL)).isEqualTo("3");
        assertThat(jdbc.queryForObject("select status from stock_reservations where order_id = ?", String.class,
                orderId)).isEqualTo("RESERVED");

        await().atMost(Duration.ofSeconds(30)).until(() ->
                KafkaTestClient.readByKey(EventTopics.ORDER_CREATED, String.valueOf(orderId)).size() == 1);
        ConsumerRecord<String, String> record =
                KafkaTestClient.readByKey(EventTopics.ORDER_CREATED, String.valueOf(orderId)).get(0);
        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(envelope.get("traceId").asText()).isEqualTo(traceId);
        assertThat(envelope.get("payload").get("totalAmount").asLong()).isEqualTo(25_800L);
        assertThat(envelope.get("payload").get("items").get(0).get("price").asLong()).isEqualTo(12_900L);
        assertThat(record.value()).doesNotContain("홍길동", "010-1234-5678", "어딘가");
    }

    @Test
    void shortProductCreatesNoOrderAndChangesNoStock() throws Exception {
        placeOrder(UUID.randomUUID().toString(), orderJson(Map.of(TOWEL, 1, SOAP, 2), false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("product-" + SOAP)));

        assertThat(jdbc.queryForObject("select count(*) from orders where member_id = ?", Integer.class, MEMBER_ID))
                .isZero();
        assertThat(products.available(TOWEL)).isEqualTo("5");
        assertThat(products.available(SOAP)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select count(*) from stock_reservations where product_id in (?, ?)",
                Integer.class, TOWEL, SOAP)).isZero();
    }

    @Test
    void retryWithTheSameKeyAfterOutOfStockIsAllowed() throws Exception {
        String key = UUID.randomUUID().toString();
        placeOrder(key, orderJson(Map.of(SOAP, 2), false)).andExpect(status().isConflict());

        placeOrder(key, orderJson(Map.of(SOAP, 1), false)).andExpect(status().isOk());
    }

    @Test
    void fiveConcurrentRequestsWithOneKeyCreateOneOrder() throws Exception {
        String key = UUID.randomUUID().toString();

        List<Object> results = runConcurrently(5, i ->
                orderService.place(MEMBER_ID, key, List.of(new OrderRequestLine(TOWEL, 1)), SHIPPING));

        List<Long> orderIds = results.stream().filter(Long.class::isInstance).map(Long.class::cast).toList();
        assertThat(orderIds).isNotEmpty();
        assertThat(orderIds).containsOnly(orderIds.get(0));
        assertThat(results).filteredOn(ShopException.class::isInstance)
                .allSatisfy(e -> assertThat(((ShopException) e).getErrorCode().name()).isEqualTo("REQUEST_IN_PROGRESS"));
        assertThat(jdbc.queryForObject("select count(*) from orders where member_id = ?", Integer.class, MEMBER_ID))
                .isEqualTo(1);
        assertThat(products.available(TOWEL)).isEqualTo("4");
        assertThat(orderService.place(MEMBER_ID, key, List.of(new OrderRequestLine(TOWEL, 1)), SHIPPING))
                .isEqualTo(orderIds.get(0));
    }

    @Test
    void lostRedisKeyStillFindsTheFirstOrderThroughTheDatabase() {
        String key = UUID.randomUUID().toString();
        long first = orderService.place(MEMBER_ID, key, List.of(new OrderRequestLine(TOWEL, 1)), SHIPPING);
        redis.delete(RedisKeys.idempotency(IdempotencyScope.ORDER, key));

        long second = orderService.place(MEMBER_ID, key, List.of(new OrderRequestLine(TOWEL, 1)), SHIPPING);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from orders where member_id = ?", Integer.class, MEMBER_ID))
                .isEqualTo(1);
        assertThat(products.available(TOWEL)).isEqualTo("4");
    }

    @Test
    void anotherMemberCannotReplayAKey() throws Exception {
        String key = UUID.randomUUID().toString();
        placeOrder(key, orderJson(Map.of(TOWEL, 1), false)).andExpect(status().isOk());

        mvc.perform(post("/api/orders").with(user(OTHER_MEMBER)).with(csrf()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson(Map.of(TOWEL, 1), false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        mvc.perform(post("/api/orders").with(user(MEMBER)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(Map.of(TOWEL, 1), false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        placeOrder(UUID.randomUUID().toString(), orderJson(Map.of(TOWEL, 11), false))
                .andExpect(status().isBadRequest());
        placeOrder("bad key!", orderJson(Map.of(TOWEL, 1), false)).andExpect(status().isBadRequest());
        placeOrder(UUID.randomUUID().toString(), "{\"items\":[],\"fromCart\":false," + addressJson() + "}")
                .andExpect(status().isBadRequest());
        assertThat(products.available(TOWEL)).isEqualTo("5");
    }

    @Test
    void orderFromCartRemovesOnlyTheOrderedProducts() throws Exception {
        redis.opsForHash().putAll(RedisKeys.cart(MEMBER_ID), Map.of(String.valueOf(TOWEL), "1",
                String.valueOf(CANDLE), "2"));

        placeOrder(UUID.randomUUID().toString(), orderJson(Map.of(TOWEL, 1), true)).andExpect(status().isOk());

        assertThat(redis.opsForHash().entries(RedisKeys.cart(MEMBER_ID))).containsOnlyKeys(String.valueOf(CANDLE));
        placeOrder(UUID.randomUUID().toString(), orderJson(Map.of(CANDLE, 1), false)).andExpect(status().isOk());
        assertThat(redis.opsForHash().entries(RedisKeys.cart(MEMBER_ID))).containsOnlyKeys(String.valueOf(CANDLE));
    }

    @Test
    void statusIsVisibleToTheOwnerOnly() throws Exception {
        long orderId = orderService.place(MEMBER_ID, UUID.randomUUID().toString(),
                List.of(new OrderRequestLine(TOWEL, 1)), SHIPPING);

        mvc.perform(get("/api/orders/" + orderId + "/status").with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.paymentStatus").doesNotExist());
        mvc.perform(get("/api/orders/" + orderId + "/status").with(user(OTHER_MEMBER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void thirtyConcurrentOrdersForTenUnitsSellExactlyTen() throws Exception {
        jdbc.update("update products set stock = 10 where id = ?", CANDLE);
        redis.opsForValue().set(RedisKeys.stock(CANDLE), "10");

        List<Object> results = runConcurrently(30, i -> orderService.place(MEMBER_ID, UUID.randomUUID().toString(),
                List.of(new OrderRequestLine(CANDLE, 1)), SHIPPING));

        assertThat(results).filteredOn(Long.class::isInstance).hasSize(10);
        assertThat(results).filteredOn(ShopException.class::isInstance).hasSize(20)
                .allSatisfy(e -> assertThat(((ShopException) e).getErrorCode().name()).isEqualTo("OUT_OF_STOCK"));
        assertThat(products.available(CANDLE)).isEqualTo("0");
        assertThat(jdbc.queryForObject("select sum(quantity) from stock_reservations where product_id = ?"
                + " and status = 'RESERVED'", Integer.class, CANDLE)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from orders where member_id = ?", Integer.class, MEMBER_ID))
                .isEqualTo(10);
    }

    @Test
    void orderPlacedWhileKafkaIsDownIsPublishedAfterItComesBack() throws Exception {
        TestInfrastructure.pauseKafka();
        long orderId;
        try {
            MvcResult result = placeOrder(UUID.randomUUID().toString(), orderJson(Map.of(TOWEL, 1), false))
                    .andExpect(status().isOk()).andReturn();
            orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("orderId").asLong();
            long id = orderId;
            // Longer than the producer's delivery.timeout.ms (10 s): the relay's send fails at least once meanwhile.
            await().during(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(15))
                    .until(() -> "PENDING".equals(outboxStatus(id)));
        } finally {
            TestInfrastructure.unpauseKafka();
        }

        long id = orderId;
        await().atMost(Duration.ofSeconds(90)).until(() -> "SENT".equals(outboxStatus(id)));
        assertThat(KafkaTestClient.readByKey(EventTopics.ORDER_CREATED, String.valueOf(orderId))).isNotEmpty();
        // An outage is not counted toward the 5 failures that end in FAILED.
        assertThat(jdbc.queryForObject("select retry_count from outbox_events where message_key = ?", Integer.class,
                String.valueOf(orderId))).isZero();
    }

    private String outboxStatus(long orderId) {
        return jdbc.queryForObject("select status from outbox_events where message_key = ?", String.class,
                String.valueOf(orderId));
    }

    private ResultActions placeOrder(String key, String json) throws Exception {
        return mvc.perform(post("/api/orders").with(user(MEMBER)).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String orderJson(Map<Long, Integer> quantities, boolean fromCart) {
        StringBuilder items = new StringBuilder();
        quantities.forEach((productId, qty) -> items.append(items.isEmpty() ? "" : ",")
                .append("{\"productId\":").append(productId).append(",\"qty\":").append(qty).append('}'));
        return "{\"items\":[" + items + "]," + addressJson() + ",\"fromCart\":" + fromCart + "}";
    }

    private static String addressJson() {
        return "\"address\":{\"receiverName\":\"홍길동\",\"receiverPhone\":\"010-1234-5678\",\"line\":\"서울시 어딘가 1\"}";
    }

    /** Starts all tasks at once; each result is the return value or the thrown exception. */
    private static List<Object> runConcurrently(int count, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Object> call = () -> {
                    start.await();
                    try {
                        return task.run(index);
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        Object run(int index);
    }
}
