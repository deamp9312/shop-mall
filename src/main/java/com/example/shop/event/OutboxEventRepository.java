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
     * 가장 오래된 PENDING 행을 이 relay용으로 잠근다. SKIP LOCKED 덕분에 다른 인스턴스는 대기하지 않고 다음 행을
     * 가져가므로 (REQ-DA-002) 한 이벤트는 한 번에 하나의 relay만 발행한다. 같은 주문(message key)의 앞선 행이 FAILED인
     * 동안 뒤 행은 보류되어 앞 행을 추월하지 않으며, 관리자 재등록으로 풀린다. 잠그는 것은 outbox 행 자체(OF o)뿐이고
     * FAILED 확인은 일반 read이다.
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

    /** 이벤트 화면용 FAILED 행을 최신순으로, 각 행이 보류시킨 PENDING 행 수와 함께 조회한다 (REQ-F-014). */
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

    /** 같은 주문의 FAILED 행 뒤에 보류된 PENDING 행 수이다. */
    @Query("""
            select count(p) from OutboxEvent p
            where p.status = com.example.shop.event.OutboxStatus.PENDING
              and exists (select 1 from OutboxEvent f where f.messageKey = p.messageKey
                          and f.status = com.example.shop.event.OutboxStatus.FAILED and f.id < p.id)
            """)
    long countHeldBack();

    /** 시도 횟수를 초기화하며 FAILED -> PENDING으로 바꾼다. 행이 (더 이상) FAILED가 아니면 0을 반환한다. */
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
