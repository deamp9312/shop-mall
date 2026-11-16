package com.example.shop.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 관리자 이벤트 화면용 주문 하나의 이벤트 이력이다 (REQ-F-014 1). outbox 행(발행)과 processed_events 행(소비)을
 * 시간순으로 병합한다. 두 테이블은 event_id로 join하며, outbox message key는 orderId이다.
 * 발행 이벤트는 outbox created_at(도메인 변경과 함께 기록됨) 시점에 배치한다. relay는 broker ack를 받은 뒤에야
 * sent_at을 찍으므로, 빠른 consumer의 processed_at보다 늦을 수 있기 때문이다.
 */
@Component
public class EventTraceQuery {

    private final JdbcTemplate jdbc;

    public EventTraceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventTraceEntry> traceOf(long orderId) {
        String messageKey = String.valueOf(orderId);
        List<EventTraceEntry> entries = new ArrayList<>(jdbc.query("""
                        select event_id, topic, status, created_at as at
                        from outbox_events where message_key = ?
                        """,
                (row, i) -> new EventTraceEntry(row.getTimestamp("at").toLocalDateTime(), EventTraceEntry.Kind.PUBLISHED,
                        row.getString("topic"), row.getString("event_id"), null, row.getString("status")),
                messageKey));
        entries.addAll(jdbc.query("""
                        select p.consumer_group, p.event_id, p.processed_at, o.topic
                        from processed_events p join outbox_events o on o.event_id = p.event_id
                        where o.message_key = ?
                        """,
                (row, i) -> new EventTraceEntry(row.getTimestamp("processed_at").toLocalDateTime(),
                        EventTraceEntry.Kind.CONSUMED, row.getString("topic"), row.getString("event_id"),
                        row.getString("consumer_group"), EventTraceEntry.PROCESSED),
                messageKey));
        entries.sort(Comparator.comparing(EventTraceEntry::at).thenComparing(EventTraceEntry::kind));
        return entries;
    }

    /**
     * 이력의 한 줄이다. result는 발행 이벤트이면 outbox 상태(PENDING / SENT / FAILED)이고, 소비 이벤트이면
     * {@link #PROCESSED}이다.
     */
    public record EventTraceEntry(LocalDateTime at, Kind kind, String topic, String eventId, String consumerGroup,
                                  String result) {

        public static final String PROCESSED = "PROCESSED";

        /** timestamp가 같을 때 발행이 소비보다 앞서도록 이 순서로 선언한다. */
        public enum Kind {
            PUBLISHED,
            CONSUMED
        }
    }
}
