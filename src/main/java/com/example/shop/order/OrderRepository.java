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

    /**
     * Owner check (REQ-SE-001 2): another member's order is reported as not found.
     *
     * 소유자 확인 (REQ-SE-001 2): 다른 회원의 주문은 없는 것으로 응답한다.
     */
    Optional<Order> findByIdAndMemberId(Long id, Long memberId);

    /**
     * SELECT ... FOR UPDATE before a status change. A change inserts an order_status_history row first (its foreign key
     * takes a shared lock on this order row) and updates the order at flush; two changes of one order would each hold
     * the shared lock and wait for the other's exclusive lock (deadlock). Taking the row lock first makes the second
     * change wait and then see the first one's result. Stock is never locked this way; it stays a Redis counter.
     *
     * 상태 변경 전에 SELECT ... FOR UPDATE를 한다. 상태 변경은 먼저 order_status_history 행을 넣고(이 외래 키가 주문
     * 행에 공유 락을 건다) flush 시점에 주문을 update한다. 한 주문의 변경 두 개가 각자 공유 락을 잡고 상대의 배타 락을
     * 기다리면 교착 상태가 된다. 행 락을 먼저 잡으면 두 번째 변경이 기다렸다가 첫 번째의 결과를 본다. 재고는 이런
     * 식으로 잠그지 않고 Redis 카운터로 둔다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForStatusChange(@Param("id") Long id);

    Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

    /**
     * Order list, newest first (REQ-F-012 1); uses idx_orders_member_created.
     *
     * 주문 목록, 최신순 (REQ-F-012 1). idx_orders_member_created를 쓴다.
     */
    Page<Order> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    /**
     * Orders whose payment deadline has passed (REQ-F-010 1); uses idx_orders_status_expires.
     *
     * 결제 기한이 지난 주문 (REQ-F-010 1). idx_orders_status_expires를 쓴다.
     */
    @Query("""
            select o.id from Order o
            where o.status = com.example.shop.order.OrderStatus.PAYMENT_PENDING and o.paymentExpiresAt <= :now
            order by o.paymentExpiresAt
            """)
    List<Long> findPaymentOverdueIds(@Param("now") LocalDateTime now, Pageable limit);
}
