package com.example.shop.stock;

import com.example.shop.common.RedisKeys;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.TestProducts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review findings Medium-1 and Medium-2 (2026-11-06) on the Redis side of reserve / release / admin adjustment:
 * <ul>
 *     <li>a release over several products must not leave some counters restored when another one fails;</li>
 *     <li>a rollback compensation must not be applied on top of a resync that already read the rolled-back DB.
 *     The resync is forced into the gap between the rollback (DB locks released) and the compensation by a
 *     synchronization registered before the code under test, so its afterCompletion runs first.</li>
 * </ul>
 */
class StockCompensationTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_480L;
    private static final long MUG = 9_481L;
    private static final long CUP = 9_482L;
    private static final long ORDER_ID = 9_481L;

    @Autowired
    private StockReservationService stock;

    @Autowired
    private StockAdjustmentService adjustment;

    @Autowired
    private AvailableStockSynchronizer synchronizer;

    @Autowired
    private StockReservationRepository reservations;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private TestProducts products;

    @BeforeEach
    void setUp() {
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(MUG, 1_000, 10);
        products.create(CUP, 1_000, 10);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(RedisKeys.stock(CUP));
        products.deleteAll();
    }

    /** Medium-1: CUP's counter is not a number, so the release fails; MUG must not keep its +3. */
    @Test
    void releaseOverSeveralProductsRestoresNothingWhenOneCounterFails() {
        reservations.save(new StockReservation(ORDER_ID, MUG, 3));
        reservations.save(new StockReservation(ORDER_ID, CUP, 3));
        redis.opsForValue().set(RedisKeys.stock(MUG), "7");
        redis.delete(RedisKeys.stock(CUP));
        redis.opsForHash().put(RedisKeys.stock(CUP), "broken", "1");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> stock.releaseReserved(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);

        assertThat(products.available(MUG)).isEqualTo("7");
        assertThat(jdbc.queryForList("select status from stock_reservations where order_id = ?", String.class,
                ORDER_ID)).containsOnly("RESERVED");
    }

    /** Medium-2, order path: reserve 3 -> rollback -> resync writes 10 -> the compensation must not make it 13. */
    @Test
    void reservationRolledBackAfterAResyncIsNotGivenBackTwice() {
        transaction.executeWithoutResult(status -> {
            resyncRightAfterRollback();
            stock.reserve(ORDER_ID, Map.of(MUG, 3));
            status.setRollbackOnly();
        });

        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.databaseStock(MUG)).isEqualTo(10);
    }

    /** Medium-2, admin path: 10 -> 5 rolled back -> resync writes 10 -> the compensation must not make it 15. */
    @Test
    void adjustmentRolledBackAfterAResyncIsNotUndoneTwice() {
        transaction.executeWithoutResult(status -> {
            resyncRightAfterRollback();
            adjustment.adjust(MUG, 5);
            status.setRollbackOnly();
        });

        assertThat(products.available(MUG)).isEqualTo("10");
        assertThat(products.databaseStock(MUG)).isEqualTo(10);
    }

    /** Medium-2, release path: release 3 rolled back -> resync writes 7 -> the compensation must not make it 4. */
    @Test
    void releaseRolledBackAfterAResyncIsNotUndoneTwice() {
        reservations.save(new StockReservation(ORDER_ID, MUG, 3));
        redis.opsForValue().set(RedisKeys.stock(MUG), "7");

        transaction.executeWithoutResult(status -> {
            resyncRightAfterRollback();
            stock.releaseReserved(ORDER_ID);
            status.setRollbackOnly();
        });

        assertThat(products.available(MUG)).isEqualTo("7");
    }

    /**
     * Runs a full resync in another thread (its own transaction) as soon as the current transaction has rolled back,
     * before any synchronization registered later.
     */
    private void resyncRightAfterRollback() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    CompletableFuture.supplyAsync(synchronizer::synchronizeFromDatabase).join();
                }
            }
        });
    }
}
