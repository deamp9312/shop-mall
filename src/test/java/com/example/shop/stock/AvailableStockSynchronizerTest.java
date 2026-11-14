package com.example.shop.stock;

import com.example.shop.common.RedisKeys;
import com.example.shop.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * REQ-F-007 6): Redis available stock = DB stock - quantity still RESERVED, also while orders are in flight
 * (the step-2 README race: a resync must not erase a reservation whose order has not committed yet).
 *
 * REQ-F-007 6): Redis 가용 재고 = DB 재고 - 아직 RESERVED인 수량. 주문이 진행 중일 때도 성립한다 (2단계 README의 경쟁
 * 상황: 재동기화가 아직 커밋되지 않은 주문의 선점을 지우면 안 된다).
 */
class AvailableStockSynchronizerTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_001L;
    private static final long PRODUCT_ID = 9_001L;

    @Autowired
    private AvailableStockSynchronizer synchronizer;

    @Autowired
    private StockReservationRepository reservations;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from stock_reservations where product_id = ?", PRODUCT_ID);
        jdbc.update("delete from products where id = ?", PRODUCT_ID);
        jdbc.update("delete from categories where id = ?", CATEGORY_ID);
        redis.delete(RedisKeys.stock(PRODUCT_ID));
    }

    @Test
    void onlyReservedQuantityIsSubtractedFromDatabaseStock() {
        insertProductWithStock50();
        reservations.save(new StockReservation(1L, PRODUCT_ID, 7));
        reservations.save(new StockReservation(2L, PRODUCT_ID, 3));
        jdbc.update("update stock_reservations set status = 'CONFIRMED' where order_id = 2 and product_id = ?",
                PRODUCT_ID);

        synchronizer.synchronizeFromDatabase();

        assertThat(redis.opsForValue().get(RedisKeys.stock(PRODUCT_ID))).isEqualTo("43");
    }

    @Test
    void resyncWaitsForAnOrderThatReservedInRedisButHasNotCommitted() throws Exception {
        insertProductWithStock50();
        redis.opsForValue().set(RedisKeys.stock(PRODUCT_ID), "50");
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        // The order transaction as OrderService runs it: reservation row first, Redis decrement, commit later.
        // OrderService가 실행하는 그대로의 주문 트랜잭션: 선점 행 먼저, Redis 차감, 커밋은 나중.
        CompletableFuture<Void> order = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            reservations.save(new StockReservation(1L, PRODUCT_ID, 7));
            redis.opsForValue().decrement(RedisKeys.stock(PRODUCT_ID), 7);
            reserved.countDown();
            awaitLatch(commit);
        }));
        assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> resync = CompletableFuture.supplyAsync(synchronizer::synchronizeFromDatabase);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> !resync.isDone());
        commit.countDown();
        order.get(10, TimeUnit.SECONDS);
        resync.get(10, TimeUnit.SECONDS);

        assertThat(redis.opsForValue().get(RedisKeys.stock(PRODUCT_ID))).isEqualTo("43");
    }

    private void insertProductWithStock50() {
        jdbc.update("insert into categories (id, name) values (?, 'sync-test')", CATEGORY_ID);
        jdbc.update("insert into products (id, category_id, name, description, price, stock, image_url, created_at,"
                + " updated_at) values (?, ?, 'sync-test', 'd', 1000, 50, '/x.svg', now(6), now(6))",
                PRODUCT_ID, CATEGORY_ID);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
