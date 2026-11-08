package com.example.shop.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Reserve, confirm and release stock (REQ-F-007, REQ-F-011 4). Every method joins the caller's transaction: the order
 * transaction for reserve, the idempotent consumer transaction for the others.
 * <p>
 * Ordering rule shared with {@link AvailableStockSynchronizer}: reservation rows are written before Redis is touched,
 * and Redis is touched before the commit, so a resync (which reads the rows with FOR SHARE) either waits for this
 * transaction or runs before it. If the transaction rolls back after Redis changed, the change is undone unless a resync
 * ran in between (resync epoch, review Medium-2). A release over several products is one Lua call (Medium-1).
 * Rows are handled in product/id order, the same order the resync locks them.
 */
@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    private final StockReservationRepository reservations;
    private final AvailableStockCounter counter;

    StockReservationService(StockReservationRepository reservations, AvailableStockCounter counter) {
        this.reservations = reservations;
        this.counter = counter;
    }

    /**
     * Records RESERVED rows and decrements Redis for every product in one Lua call.
     *
     * @param quantities productId -> quantity
     * @throws OutOfStockException when any product is short; nothing is decremented and the caller rolls back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(long orderId, Map<Long, Integer> quantities) {
        SortedMap<Long, Integer> byProduct = new TreeMap<>(quantities);
        byProduct.forEach((productId, quantity) ->
                reservations.save(new StockReservation(orderId, productId, quantity)));
        String epochBefore = counter.resyncEpoch();
        List<Long> shortProducts = counter.tryReserve(byProduct);
        if (!shortProducts.isEmpty()) {
            log.info("stock reservation rejected: short products={}", shortProducts);
            throw new OutOfStockException(shortProducts);
        }
        AfterRollback.undo(counter, AvailableStockCounter.negated(byProduct), epochBefore);
        log.info("stock reserved: {}", byProduct);
    }

    /** payment.completed (REQ-F-007 3): RESERVED -> CONFIRMED and the DB stock goes down. Redis is unchanged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(long orderId) {
        for (StockReservation reservation : reservations.findByOrderIdOrderById(orderId)) {
            if (reservations.changeStatus(reservation.getId(), StockReservationStatus.RESERVED,
                    StockReservationStatus.CONFIRMED) == 1) {
                reservations.addDatabaseStock(reservation.getProductId(), -reservation.getQuantity());
            } else {
                log.warn("reservation not confirmed, it is no longer RESERVED: reservationId={}", reservation.getId());
            }
        }
        log.info("stock confirmed");
    }

    /** payment.failed (REQ-F-007 4): RESERVED -> RELEASED and Redis gets the quantity back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReserved(long orderId) {
        release(orderId, List.of(StockReservationStatus.RESERVED));
    }

    /**
     * order.cancelled (REQ-F-011 4): a RESERVED row goes back to Redis; a CONFIRMED row (paid order) goes back to both
     * the DB stock and Redis.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseAll(long orderId) {
        release(orderId, List.of(StockReservationStatus.RESERVED, StockReservationStatus.CONFIRMED));
    }

    private void release(long orderId, List<StockReservationStatus> releasable) {
        SortedMap<Long, Integer> restored = new TreeMap<>();
        for (StockReservation reservation : reservations.findByOrderIdOrderById(orderId)) {
            for (StockReservationStatus from : releasable) {
                if (reservations.changeStatus(reservation.getId(), from, StockReservationStatus.RELEASED) == 1) {
                    restoreDatabaseStockIfConfirmed(reservation, from);
                    restored.merge(reservation.getProductId(), reservation.getQuantity(), Integer::sum);
                    break;
                }
            }
        }
        if (restored.isEmpty()) {
            log.info("nothing to release for this order");
            return;
        }
        String epochBefore = counter.resyncEpoch();
        counter.add(restored);
        AfterRollback.undo(counter, restored, epochBefore);
        log.info("stock released: {}", restored);
    }

    private void restoreDatabaseStockIfConfirmed(StockReservation reservation, StockReservationStatus from) {
        if (from == StockReservationStatus.CONFIRMED) {
            reservations.addDatabaseStock(reservation.getProductId(), reservation.getQuantity());
        }
    }
}
