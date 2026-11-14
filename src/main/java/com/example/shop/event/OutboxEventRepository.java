package com.example.shop.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Oldest PENDING rows, locked for this relay. SKIP LOCKED lets another instance take the next rows instead of
     * waiting (REQ-DA-002), so one event is sent by one relay at a time. A row is held back while an earlier row of the
     * same order (message key) is FAILED, so it never overtakes it; the admin re-queue releases it. Only the outbox
     * rows themselves are locked (OF o), the FAILED check is a plain read.
     *
     * 가장 오래된 PENDING 행을 이 릴레이용으로 잠근다. SKIP LOCKED 덕분에 다른 인스턴스는 기다리지 않고 다음 행을
     * 가져가므로 (REQ-DA-002), 한 이벤트는 한 번에 한 릴레이만 보낸다. 같은 주문(메시지 키)의 앞선 행이 FAILED이면 그
     * 행을 앞지르지 않도록 보류하며, 관리자가 다시 대기열에 넣으면 풀린다. 잠그는 것은 아웃박스 행 자체뿐이고(OF o),
     * FAILED 검사는 일반 읽기다.
     */
    @Query(value = """
            select o.* from outbox_events o
            where o.status = 'PENDING'
              and not exists (select 1 from outbox_events f
                              where f.message_key = o.message_key and f.status = 'FAILED' and f.id < o.id)
            order by o.id
            limit :limit
            for update of o skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("limit") int limit);

    /**
     * FAILED rows for the event screen (REQ-F-014), newest first, with the PENDING rows each one holds back.
     *
     * 이벤트 화면용 FAILED 행 (REQ-F-014). 최신순이며 각 행이 붙잡고 있는 PENDING 행 수를 함께 준다.
     */
    @Query("""
            select new com.example.shop.event.FailedOutboxEvent(f.id, f.eventId, f.topic, f.messageKey, f.retryCount,
                f.createdAt,
                (select count(p) from OutboxEvent p where p.messageKey = f.messageKey
                    and p.status = com.example.shop.event.OutboxStatus.PENDING and p.id > f.id))
            from OutboxEvent f
            where f.status = com.example.shop.event.OutboxStatus.FAILED
            order by f.id desc
            limit 50
            """)
    List<FailedOutboxEvent> findRecentFailed();

    /**
     * PENDING rows held back behind a FAILED row of the same order.
     *
     * 같은 주문의 FAILED 행 뒤에 보류된 PENDING 행.
     */
    @Query("""
            select count(p) from OutboxEvent p
            where p.status = com.example.shop.event.OutboxStatus.PENDING
              and exists (select 1 from OutboxEvent f where f.messageKey = p.messageKey
                          and f.status = com.example.shop.event.OutboxStatus.FAILED and f.id < p.id)
            """)
    long countHeldBack();

    /**
     * FAILED -> PENDING with a fresh attempt count; 0 when the row is not FAILED (any more).
     *
     * FAILED -> PENDING으로 바꾸고 시도 횟수를 초기화한다. 행이 FAILED가 아니면(더 이상 아니면) 0.
     */
    @Modifying
    @Query("""
            update OutboxEvent e
            set e.status = com.example.shop.event.OutboxStatus.PENDING, e.retryCount = 0
            where e.id = :id and e.status = com.example.shop.event.OutboxStatus.FAILED
            """)
    int requeueFailed(@Param("id") long id);

    Optional<OutboxEvent> findByEventId(String eventId);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = com.example.shop.event.OutboxStatus.SENT and e.sentAt < :before")
    int deleteSentBefore(@Param("before") LocalDateTime before);
}
