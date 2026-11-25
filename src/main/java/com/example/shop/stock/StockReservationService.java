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
 * 재고 선점·확정·해제 (REQ-F-007, REQ-F-011 4). 모든 메서드는 호출자의 트랜잭션에 참여한다. 선점은 주문
 * 트랜잭션에, 나머지는 멱등 컨슈머 트랜잭션에 참여한다.
 * <p>
 * {@link AvailableStockSynchronizer}와 공유하는 순서 규칙: Redis를 건드리기 전에 선점 행을 쓰고, 커밋 전에 Redis를
 * 건드린다. 따라서 재동기화(선점 행을 FOR SHARE로 읽음)는 이 트랜잭션을 기다리거나 그보다 먼저 실행된다. Redis 변경 후
 * 트랜잭션이 롤백되면, 그 사이 재동기화가 실행되지 않은 경우에만 변경을 되돌린다 (resync epoch, review Medium-2).
 * 여러 상품의 해제는 한 번의 Lua 호출로 처리한다 (Medium-1). 행은 재동기화가 잠그는 순서와 같은 상품/id 순으로 처리한다.
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
     * RESERVED 행을 기록하고, 모든 상품의 Redis 재고를 한 번의 Lua 호출로 차감한다.
     * <pre>
     * [IN]  orderId=7, quantities={3: 2}
     * [DB]    stock_reservations INSERT (order_id=7, product_id=3, quantity=2, status=RESERVED)  ← 아직 커밋 전
     * [Redis] GET shop:stock-sync:epoch → "12"
     *         EVAL stock-reserve.lua KEYS=[shop:stock:3] ARGV=["2"] → 50 → 48, return []
     *         ※ Redis는 DB 트랜잭션에 묶이지 않는다. 그래서 주문 트랜잭션이 나중에 롤백되면
     *           AfterRollback이 INCRBY shop:stock:3 2 로 직접 되돌린다 (보상)
     * [OUT] 없음 / 재고 부족이면 OutOfStockException([3]) → 주문 트랜잭션 롤백
     * </pre>
     *
     * @param quantities productId -> quantity
     * @throws OutOfStockException 재고가 부족한 상품이 하나라도 있을 때. 아무것도 차감하지 않으며 호출자가 롤백한다
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

    /** payment.completed (REQ-F-007 3): RESERVED -> CONFIRMED로 바꾸고 DB 재고를 차감한다. Redis는 그대로다. */
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

    /** payment.failed (REQ-F-007 4): RESERVED -> RELEASED로 바꾸고 Redis에 수량을 되돌린다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReserved(long orderId) {
        release(orderId, List.of(StockReservationStatus.RESERVED));
    }

    /**
     * order.cancelled (REQ-F-011 4): RESERVED 행은 Redis로 되돌리고, CONFIRMED 행(결제 완료 주문)은 DB 재고와
     * Redis 모두에 되돌린다.
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
