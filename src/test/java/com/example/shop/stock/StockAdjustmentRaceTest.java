package com.example.shop.stock;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.RedisKeys;
import com.example.shop.common.ShopException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Step-3 defect: the admin adjustment checked "not below the reserved quantity" and then updated the DB, so an order
 * reserving in between (row inserted, Redis decremented, not committed yet) was not counted and Redis went negative.
 * The adjustment now locks reservations (FOR SHARE) and then the product (FOR UPDATE), the resync's lock order, and
 * moves Redis before it commits.
 *
 * 3단계 결함: 관리자 조정이 "선점 수량보다 낮을 수 없다"를 검사한 뒤 DB를 update했기 때문에, 그 사이에 선점한 주문(행
 * insert, Redis 차감, 아직 커밋 전)이 반영되지 않아 Redis가 음수가 되었다.
 * 이제 조정은 재동기화와 같은 락 순서로 선점 행(FOR SHARE), 그다음 상품(FOR UPDATE)을 잠그고, 커밋 전에 Redis를
 * 움직인다.
 */
class StockAdjustmentRaceTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_470L;
    private static final long PRODUCT_ID = 9_471L;
    private static final long ORDER_ID = 9_471L;

    @Autowired
    private StockAdjustmentService adjustment;

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
    void adjustmentWaitsForAnInFlightReservationAndCountsIt() throws Exception {
        insertProductWithStock(10);
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> order = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            reservations.save(new StockReservation(ORDER_ID, PRODUCT_ID, 5));
            redis.opsForValue().decrement(RedisKeys.stock(PRODUCT_ID), 5);
            reserved.countDown();
            awaitLatch(commit);
        }));
        assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> adjust = CompletableFuture.runAsync(() -> adjustment.adjust(PRODUCT_ID, 3));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> !adjust.isDone());
        commit.countDown();
        order.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> adjust.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(ShopException.class)
                .extracting(e -> ((ShopException) e).getErrorCode()).isEqualTo(ErrorCode.STOCK_BELOW_RESERVED);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, PRODUCT_ID))
                .isEqualTo(10);
        assertThat(redis.opsForValue().get(RedisKeys.stock(PRODUCT_ID))).isEqualTo("5");
    }

    @Test
    void adjustmentMovesDatabaseAndRedisByTheSameDelta() {
        insertProductWithStock(10);
        reservations.save(new StockReservation(ORDER_ID, PRODUCT_ID, 4));
        redis.opsForValue().set(RedisKeys.stock(PRODUCT_ID), "6");

        adjustment.adjust(PRODUCT_ID, 7);

        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, PRODUCT_ID))
                .isEqualTo(7);
        assertThat(redis.opsForValue().get(RedisKeys.stock(PRODUCT_ID))).isEqualTo("3");
    }

    private void insertProductWithStock(int stock) {
        jdbc.update("insert into categories (id, name) values (?, 'adjust-race')", CATEGORY_ID);
        jdbc.update("insert into products (id, category_id, name, description, price, stock, image_url, created_at,"
                + " updated_at) values (?, ?, 'adjust-race', 'd', 1000, ?, '/x.svg', now(6), now(6))",
                PRODUCT_ID, CATEGORY_ID, stock);
        redis.opsForValue().set(RedisKeys.stock(PRODUCT_ID), String.valueOf(stock));
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
