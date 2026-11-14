package com.example.shop.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Stock module queries. The stock module owns products.stock (confirm and admin adjustment), so the product stock
 * column is read and written here rather than in the product module.
 *
 * 재고 모듈 쿼리. products.stock은 재고 모듈이 소유하므로(확정, 관리자 조정), 상품 재고 컬럼은 상품 모듈이 아니라
 * 여기서 읽고 쓴다.
 */
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /**
     * Locking read (FOR SHARE) of the reserved quantity per product, for the resync. It waits for transactions that
     * inserted or changed reservation rows and are not committed yet, and blocks new ones until the resync commits.
     * Rows: [productId, reservedQuantity].
     *
     * 재동기화용으로 상품별 선점 수량을 잠금 읽기(FOR SHARE)한다. 선점 행을 넣거나 바꾸고 아직 커밋하지 않은 트랜잭션을
     * 기다리고, 재동기화가 커밋될 때까지 새 트랜잭션을 막는다.
     * 행: [productId, reservedQuantity].
     */
    @Query(value = """
            select product_id, sum(quantity) from stock_reservations
            where status = 'RESERVED'
            group by product_id
            for share
            """, nativeQuery = true)
    List<Object[]> lockReservedQuantities();

    /**
     * Locking read (FOR SHARE) of every product's DB stock, for the resync. Rows: [productId, stock].
     *
     * 재동기화용으로 모든 상품의 DB 재고를 잠금 읽기(FOR SHARE)한다. 행: [productId, stock].
     */
    @Query(value = "select id, stock from products for share", nativeQuery = true)
    List<Object[]> lockDatabaseStocks();

    List<StockReservation> findByOrderIdOrderById(Long orderId);

    /**
     * Conditional status change; returns 1 only for the caller that moved the row out of {@code from}.
     *
     * 조건부 상태 변경. 행을 {@code from} 상태에서 옮긴 호출자에게만 1을 돌려준다.
     */
    @Modifying
    @Query("""
            update StockReservation r set r.status = :to, r.updatedAt = current_timestamp
            where r.id = :id and r.status = :from
            """)
    int changeStatus(@Param("id") Long id, @Param("from") StockReservationStatus from,
                     @Param("to") StockReservationStatus to);

    /**
     * Confirm (REQ-F-007 3) and restore after a paid order is cancelled (REQ-F-011 4); delta may be negative.
     *
     * 확정 (REQ-F-007 3)과 결제된 주문 취소 후 복원 (REQ-F-011 4). delta는 음수일 수 있다.
     */
    @Modifying
    @Query("""
            update Product p set p.stock = p.stock + :delta, p.updatedAt = current_timestamp
            where p.id = :productId
            """)
    int addDatabaseStock(@Param("productId") Long productId, @Param("delta") int delta);

    /**
     * Locking read (FOR SHARE) of one product's RESERVED quantity, for the admin adjustment. Next-key locks on
     * (product_id, status) also block a new reservation row for the product until the adjustment commits.
     *
     * 관리자 조정용으로 상품 하나의 RESERVED 수량을 잠금 읽기(FOR SHARE)한다. (product_id, status)의 next-key 락이
     * 조정이 커밋될 때까지 그 상품의 새 선점 행도 막는다.
     */
    @Query(value = """
            select coalesce(sum(quantity), 0) from stock_reservations
            where product_id = :productId and status = 'RESERVED'
            for share
            """, nativeQuery = true)
    long lockReservedQuantity(@Param("productId") Long productId);

    /**
     * Locks the product row the adjustment is about to change; taken after the reservations (resync lock order).
     *
     * 조정이 바꿀 상품 행을 잠근다. 선점 행 다음에 잡는다 (재동기화 락 순서).
     */
    @Query(value = "select stock from products where id = :productId for update", nativeQuery = true)
    Optional<Integer> lockDatabaseStock(@Param("productId") Long productId);

    @Modifying
    @Query("""
            update Product p set p.stock = :newStock, p.updatedAt = current_timestamp
            where p.id = :productId
            """)
    int setDatabaseStock(@Param("productId") Long productId, @Param("newStock") int newStock);
}
