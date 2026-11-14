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
 *
 * 재고 선점, 확정, 해제 (REQ-F-007, REQ-F-011 4). 모든 메서드가 호출자의 트랜잭션에 참여한다. 선점은 주문 트랜잭션,
 * 나머지는 멱등 컨슈머 트랜잭션이다.
 * <p>
 * {@link AvailableStockSynchronizer}와 공유하는 순서 규칙: Redis를 건드리기 전에 선점 행을 쓰고, 커밋 전에 Redis를
 * 건드린다. 그래서 재동기화(행을 FOR SHARE로 읽음)는 이 트랜잭션을 기다리거나 그보다 먼저 실행된다. Redis가 바뀐 뒤
 * 트랜잭션이 롤백되면, 그 사이 재동기화가 실행되지 않았을 때만 변경을 되돌린다 (재동기화 epoch, 리뷰 Medium-2). 여러
 * 상품의 해제는 Lua 호출 한 번이다 (Medium-1).
 * 행은 product/id 순서로 처리한다. 재동기화가 잠그는 순서와 같다.
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
     * RESERVED 행을 기록하고 모든 상품의 Redis 재고를 Lua 호출 한 번으로 차감한다.
     * quantities: productId -> 수량
     * 예외: 부족한 상품이 하나라도 있으면 OutOfStockException. 아무것도 차감하지 않으며 호출자가 롤백한다.
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

    /**
     * payment.completed (REQ-F-007 3): RESERVED -> CONFIRMED and the DB stock goes down. Redis is unchanged.
     *
     * payment.completed (REQ-F-007 3): RESERVED -> CONFIRMED, DB 재고가 줄어든다. Redis는 바뀌지 않는다.
     */
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

    /**
     * payment.failed (REQ-F-007 4): RESERVED -> RELEASED and Redis gets the quantity back.
     *
     * payment.failed (REQ-F-007 4): RESERVED -> RELEASED, Redis에 수량을 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReserved(long orderId) {
        release(orderId, List.of(StockReservationStatus.RESERVED));
    }

    /**
     * order.cancelled (REQ-F-011 4): a RESERVED row goes back to Redis; a CONFIRMED row (paid order) goes back to both
     * the DB stock and Redis.
     *
     * order.cancelled (REQ-F-011 4): RESERVED 행은 Redis로 돌려주고, CONFIRMED 행(결제된 주문)은 DB 재고와 Redis 모두에
     * 돌려준다.
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
