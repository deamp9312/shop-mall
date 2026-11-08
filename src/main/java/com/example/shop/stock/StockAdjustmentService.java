package com.example.shop.stock;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.RedisLock;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Admin stock adjustment (REQ-F-013 2). The DB stock is set to the new value and the Redis counter moves by the same
 * delta with INCRBY. A delta (instead of overwriting) keeps reservations made by concurrent orders intact.
 * <p>
 * Same rules as the resync ({@link AvailableStockSynchronizer}): reservations are read with FOR SHARE first, then the
 * product row is locked, and Redis moves before the commit. An order that has reserved but not committed makes the
 * adjustment wait and is then counted; an order that starts later waits for the adjustment. So the check "not below
 * the reserved quantity" still holds when the DB is updated.
 */
@Service
public class StockAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(StockAdjustmentService.class);

    private final StockReservationRepository reservations;
    private final AvailableStockCounter counter;
    private final RedisLock lock;
    private final TransactionTemplate transaction;

    StockAdjustmentService(StockReservationRepository reservations, AvailableStockCounter counter,
                                  RedisLock lock, TransactionTemplate transaction) {
        this.reservations = reservations;
        this.counter = counter;
        this.lock = lock;
        this.transaction = transaction;
    }

    /** Runs under the resync lock as well, so a full resync never overwrites the counter halfway through. */
    public void adjust(long productId, int newStock) {
        lock.runExclusively(AvailableStockSynchronizer.LOCK_NAME, AvailableStockSynchronizer.LOCK_TTL,
                ErrorCode.STOCK_SYNC_IN_PROGRESS, () -> transaction.execute(status -> adjustLocked(productId, newStock)));
    }

    private int adjustLocked(long productId, int newStock) {
        long reserved = reservations.lockReservedQuantity(productId);
        int currentStock = reservations.lockDatabaseStock(productId)
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
        if (newStock < reserved) {
            throw new ShopException(ErrorCode.STOCK_BELOW_RESERVED);
        }
        reservations.setDatabaseStock(productId, newStock);
        Map<Long, Integer> delta = Map.of(productId, newStock - currentStock);
        String epochBefore = counter.resyncEpoch();
        counter.add(delta);
        AfterRollback.undo(counter, delta, epochBefore);
        log.info("stock adjusted: productId={} newStock={} delta={}", productId, newStock, delta.get(productId));
        return delta.get(productId);
    }
}
