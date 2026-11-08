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
 */
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /**
     * Locking read (FOR SHARE) of the reserved quantity per product, for the resync. It waits for transactions that
     * inserted or changed reservation rows and are not committed yet, and blocks new ones until the resync commits.
     * Rows: [productId, reservedQuantity].
     */
    @Query(value = """
            select product_id, sum(quantity) from stock_reservations
            where status = 'RESERVED'
            group by product_id
            for share
            """, nativeQuery = true)
    List<Object[]> lockReservedQuantities();

    /** Locking read (FOR SHARE) of every product's DB stock, for the resync. Rows: [productId, stock]. */
    @Query(value = "select id, stock from products for share", nativeQuery = true)
    List<Object[]> lockDatabaseStocks();

    List<StockReservation> findByOrderIdOrderById(Long orderId);

    /** Conditional status change; returns 1 only for the caller that moved the row out of {@code from}. */
    @Modifying
    @Query("""
            update StockReservation r set r.status = :to, r.updatedAt = current_timestamp
            where r.id = :id and r.status = :from
            """)
    int changeStatus(@Param("id") Long id, @Param("from") StockReservationStatus from,
                     @Param("to") StockReservationStatus to);

    /** Confirm (REQ-F-007 3) and restore after a paid order is cancelled (REQ-F-011 4); delta may be negative. */
    @Modifying
    @Query("""
            update Product p set p.stock = p.stock + :delta, p.updatedAt = current_timestamp
            where p.id = :productId
            """)
    int addDatabaseStock(@Param("productId") Long productId, @Param("delta") int delta);

    /**
     * Locking read (FOR SHARE) of one product's RESERVED quantity, for the admin adjustment. Next-key locks on
     * (product_id, status) also block a new reservation row for the product until the adjustment commits.
     */
    @Query(value = """
            select coalesce(sum(quantity), 0) from stock_reservations
            where product_id = :productId and status = 'RESERVED'
            for share
            """, nativeQuery = true)
    long lockReservedQuantity(@Param("productId") Long productId);

    /** Locks the product row the adjustment is about to change; taken after the reservations (resync lock order). */
    @Query(value = "select stock from products where id = :productId for update", nativeQuery = true)
    Optional<Integer> lockDatabaseStock(@Param("productId") Long productId);

    @Modifying
    @Query("""
            update Product p set p.stock = :newStock, p.updatedAt = current_timestamp
            where p.id = :productId
            """)
    int setDatabaseStock(@Param("productId") Long productId, @Param("newStock") int newStock);
}
