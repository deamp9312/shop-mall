package com.example.shop.stock;

import com.example.shop.common.TraceIdFilter;
import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.EventTopics;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.KafkaTestClient;
import com.example.shop.support.OrderFlow;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * REQ-NF-001 1), 2): 200 members order the last 100 units of one product at the same moment through the order API.
 * Exactly 100 orders succeed and nothing is oversold; then 60 of them pay and 40 fail, and the DB stock and the Redis
 * available stock both end at 40. Run 10 times in a row for verification 1) (see README "동시성 검증").
 */
class StockConcurrencyTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_500L;
    private static final long PRODUCT = 9_501L;
    private static final int STOCK = 100;
    private static final int BUYERS = 200;
    private static final int PAID = 60;
    private static final long FIRST_MEMBER_ID = 9_500_000L;
    private static final int TASK_TIMEOUT_SECONDS = 120;

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

    private TestProducts products;
    private OrderFlow flow;

    @BeforeEach
    void setUp() {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter).apply(springSecurity())
                .build();
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(PRODUCT, 1_000, STOCK);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        flow.deleteOrdersOf(LongStream.range(FIRST_MEMBER_ID, FIRST_MEMBER_ID + BUYERS).toArray());
        products.deleteAll();
    }

    @Test
    void twoHundredConcurrentOrdersForAHundredUnitsSellExactlyAHundred() throws Exception {
        List<Order> orders = placeConcurrently();

        assertThat(orders).filteredOn(Order::accepted).hasSize(STOCK);
        assertThat(orders).filteredOn(order -> !order.accepted())
                .hasSize(BUYERS - STOCK)
                .allSatisfy(order -> {
                    assertThat(order.httpStatus()).isEqualTo(409);
                    assertThat(order.body()).contains("OUT_OF_STOCK");
                });
        assertThat(products.available(PRODUCT)).isEqualTo("0");
        assertThat(reservedQuantity("RESERVED")).isEqualTo(STOCK);
        assertThat(jdbc.queryForObject("select count(*) from orders where member_id between ? and ?", Integer.class,
                FIRST_MEMBER_ID, FIRST_MEMBER_ID + BUYERS - 1)).isEqualTo(STOCK);
        assertThat(products.databaseStock(PRODUCT)).isEqualTo(STOCK);
        // Let the relay and the payment consumer finish the 100 order.created events before cleanUp deletes their
        // rows; deleting under them deadlocked now and then and left this product behind for the next test.
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> readyPayments() == STOCK);
    }

    @Test
    void sixtyPaidAndFortyFailedLeaveFortyInTheDatabaseAndInRedis() throws Exception {
        List<Order> accepted = placeConcurrently().stream().filter(Order::accepted).toList();
        assertThat(accepted).hasSize(STOCK);
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> readyPayments() == STOCK);

        List<Integer> statuses = runConcurrently(accepted.size(), i -> {
            Order order = accepted.get(i);
            return flow.approve(order.member(), order.id(), i < PAID ? "SUCCESS" : "FAIL").andReturn().getResponse()
                    .getStatus();
        });
        assertThat(statuses).containsOnly(200);

        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> reservedQuantity("RESERVED") == 0);
        KafkaTestClient.awaitConsumed(ConsumerGroups.STOCK, EventTopics.PAYMENT_COMPLETED);
        KafkaTestClient.awaitConsumed(ConsumerGroups.STOCK, EventTopics.PAYMENT_FAILED);
        await().atMost(OrderFlow.ASYNC_TIMEOUT).until(() -> ordersIn("PAID") == PAID
                && ordersIn("PAYMENT_FAILED") == STOCK - PAID);

        assertThat(reservedQuantity("CONFIRMED")).isEqualTo(PAID);
        assertThat(reservedQuantity("RELEASED")).isEqualTo(STOCK - PAID);
        assertThat(products.databaseStock(PRODUCT)).isEqualTo(STOCK - PAID);
        assertThat(products.available(PRODUCT)).isEqualTo(String.valueOf(STOCK - PAID));
    }

    /** Every buyer is a different member ordering one unit, all released at once. */
    private List<Order> placeConcurrently() throws Exception {
        return runConcurrently(BUYERS, i -> {
            MemberPrincipal member = buyer(i);
            MvcResult result = flow.attempt(member, Map.of(PRODUCT, 1)).andReturn();
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();
            long orderId = status == 200 ? flow.placedOrder(result).id() : 0;
            return new Order(member, orderId, status, body);
        });
    }

    private static MemberPrincipal buyer(int index) {
        long memberId = FIRST_MEMBER_ID + index;
        return new MemberPrincipal(memberId, "buyer-" + memberId + "@shop.local", "구매자" + index, MemberRole.USER,
                null);
    }

    private int reservedQuantity(String status) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from stock_reservations where product_id = ?"
                + " and status = ?", Integer.class, PRODUCT, status);
    }

    private int readyPayments() {
        return jdbc.queryForObject("select count(*) from payments p join orders o on o.id = p.order_id"
                + " where o.member_id between ? and ? and p.status = 'READY'", Integer.class,
                FIRST_MEMBER_ID, FIRST_MEMBER_ID + BUYERS - 1);
    }

    private int ordersIn(String status) {
        return jdbc.queryForObject("select count(*) from orders where member_id between ? and ? and status = ?",
                Integer.class, FIRST_MEMBER_ID, FIRST_MEMBER_ID + BUYERS - 1, status);
    }

    /** Starts every task at the same moment (one thread each) and returns the results in task order. */
    private static <T> List<T> runConcurrently(int count, ThrowingTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.run(index);
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingTask<T> {
        T run(int index) throws Exception;
    }

    private record Order(MemberPrincipal member, long id, int httpStatus, String body) {
        boolean accepted() {
            return httpStatus == 200;
        }
    }
}
