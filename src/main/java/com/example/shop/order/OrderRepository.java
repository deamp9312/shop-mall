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

    /** 소유자 검사 (REQ-SE-001 2): 다른 회원의 주문은 찾을 수 없는 것으로 처리한다. */
    Optional<Order> findByIdAndMemberId(Long id, Long memberId);

    /**
     * 상태 변경 전에 SELECT ... FOR UPDATE를 수행한다. 상태 변경은 order_status_history 행을 먼저 insert하고(이 외래 키가
     * 주문 행에 공유 락을 건다) flush 시점에 주문을 update한다. 한 주문에 대한 두 변경은 각자 공유 락을 쥔 채 상대의
     * 배타 락을 기다리게 된다(데드락). 행 락을 먼저 잡으면 두 번째 변경이 대기한 뒤 첫 번째 변경의 결과를 보게 된다.
     * 재고는 이런 방식으로 잠그지 않으며, 계속 Redis 카운터로 관리한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForStatusChange(@Param("id") Long id);

    Optional<Order> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

    /** 최신순 주문 목록 (REQ-F-012 1). idx_orders_member_created를 사용한다. */
    Page<Order> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    /** 결제 기한이 지난 주문 (REQ-F-010 1). idx_orders_status_expires를 사용한다. */
    @Query("""
            select o.id from Order o
            where o.status = com.example.shop.order.OrderStatus.PAYMENT_PENDING and o.paymentExpiresAt <= :now
            order by o.paymentExpiresAt
            """)
    List<Long> findPaymentOverdueIds(@Param("now") LocalDateTime now, Pageable limit);
}
