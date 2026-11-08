package com.example.shop.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Owner check (REQ-SE-001 2): another member's order is reported as not found. */
    Optional<Order> findByIdAndMemberId(Long id, Long memberId);

    /**
     * SELECT ... FOR UPDATE before a status change. A change inserts an order_status_history row first (its foreign key
     * takes a shared lock on this order row) and updates the order at flush; two changes of one order would each hold
     * the shared lock and wait for the other's exclusive lock (deadlock). Taking the row lock first makes the second
     * change wait and then see the first one's result. Stock is never locked this way; it stays a Redis counter.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForStatusChange(@Param("id") Long id);

    Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

    /** Order list, newest first (REQ-F-012 1); uses idx_orders_member_created. */
    Page<Order> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    /** Orders whose payment deadline has passed (REQ-F-010 1); uses idx_orders_status_expires. */
    @Query("""
            select o.id from Order o
            where o.status = com.example.shop.order.OrderStatus.PAYMENT_PENDING and o.paymentExpiresAt <= :now
            order by o.paymentExpiresAt
            """)
    List<Long> findPaymentOverdueIds(@Param("now") LocalDateTime now, Pageable limit);
}
