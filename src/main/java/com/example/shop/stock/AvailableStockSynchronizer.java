package com.example.shop.stock;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.RedisKeys;
import com.example.shop.common.RedisLock;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the Redis available-stock counters from the database, which is the source of truth: DB stock minus the
 * quantity still RESERVED (REQ-F-007 6). Runs at startup and from the admin button (REQ-F-013 4).
 * <p>
 * It overwrites the counters, so it holds the lock RedisKeys.lock(LOCK_NAME); the admin stock adjustment takes the same lock.
 * Orders and stock consumers do not take that lock. Instead the DB is read with FOR SHARE and the counters are written
 * before that transaction commits: an order that already reserved in Redis has its reservation row inserted but not
 * committed, so the read waits for it, and an order that starts later waits for the resync. Stock consumers change the
 * reservation rows before touching Redis inside their transaction, so the same rule covers them.
 */
@Component
public class AvailableStockSynchronizer {

    static final String LOCK_NAME = "stock-resync";
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(AvailableStockSynchronizer.class);

    private final StockReservationRepository reservations;
    private final StringRedisTemplate redis;
    private final RedisLock lock;
    private final TransactionTemplate transaction;

    public AvailableStockSynchronizer(StockReservationRepository reservations, StringRedisTemplate redis,
                                      RedisLock lock, TransactionTemplate transactionTemplate) {
        this.reservations = reservations;
        this.redis = redis;
        this.lock = lock;
        this.transaction = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeAtStartup() {
        try {
            synchronizeFromDatabase();
        } catch (ShopException busy) {
            log.info("stock resync skipped at startup: another instance holds the lock");
        }
    }

    /** @return number of products whose counter was rewritten */
    public int synchronizeFromDatabase() {
        return lock.runExclusively(LOCK_NAME, LOCK_TTL, ErrorCode.STOCK_SYNC_IN_PROGRESS, this::rewriteCounters);
    }

    private int rewriteCounters() {
        return transaction.execute(status -> writeCounters(lockAvailableStocks()));
    }

    /** Reservations first, then products: the same lock order as the stock consumers (no deadlock cycle). */
    private List<AvailableStock> lockAvailableStocks() {
        Map<Long, Long> reserved = new HashMap<>();
        for (Object[] row : reservations.lockReservedQuantities()) {
            reserved.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return reservations.lockDatabaseStocks().stream()
                .map(row -> {
                    long productId = ((Number) row[0]).longValue();
                    long stock = ((Number) row[1]).longValue();
                    return new AvailableStock(productId, stock - reserved.getOrDefault(productId, 0L));
                })
                .toList();
    }

    /**
     * The epoch goes up after the database was read and before the first counter is written: a rollback compensation
     * still pending at this point would be applied on top of a value that already reflects the rollback, so it is
     * skipped ({@link AvailableStockCounter#addUnlessResynced}).
     */
    private int writeCounters(List<AvailableStock> stocks) {
        redis.opsForValue().increment(RedisKeys.stockSyncEpoch());
        for (AvailableStock stock : stocks) {
            if (stock.quantity() < 0) {
                log.warn("available stock is negative: productId={} quantity={}", stock.productId(), stock.quantity());
            }
            redis.opsForValue().set(RedisKeys.stock(stock.productId()), String.valueOf(stock.quantity()));
        }
        log.info("available stock synchronized from database: products={}", stocks.size());
        return stocks.size();
    }
}
