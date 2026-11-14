package com.example.shop.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Event history of one order for the admin event screen (REQ-F-014 1): outbox rows (published) and processed_events
 * rows (consumed), merged in time order. Both tables are joined on event_id; the outbox message key is the orderId.
 * A published event is placed at its outbox created_at (recorded with the domain change): the relay stamps sent_at only
 * after the broker's ack, which can be later than a fast consumer's processed_at.
 *
 * 관리자 이벤트 화면용 주문 한 건의 이벤트 이력 (REQ-F-014 1). 아웃박스 행(발행)과 processed_events 행(소비)을
 * 시간순으로 합친다. 두 테이블은 event_id로 연결하고, 아웃박스 메시지 키는 orderId다.
 * 발행 이벤트는 아웃박스 created_at(도메인 변경과 함께 기록) 시점에 놓는다. 릴레이는 브로커 ack를 받은 뒤에야 sent_at을
 * 찍으므로, 빠른 컨슈머의 processed_at보다 늦을 수 있기 때문이다.
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
     * One line of the history. result is the outbox status (PENDING / SENT / FAILED) for a published event and
     * {@link #PROCESSED} for a consumed one.
     *
     * 이력 한 줄. result는 발행 이벤트면 아웃박스 상태(PENDING / SENT / FAILED), 소비 이벤트면 {@link #PROCESSED}다.
     */
    public record EventTraceEntry(LocalDateTime at, Kind kind, String topic, String eventId, String consumerGroup,
                                  String result) {

        public static final String PROCESSED = "PROCESSED";

        /**
         * Declared in the order a publish precedes its consumption when both have the same timestamp.
         *
         * 시각이 같을 때 발행이 소비보다 앞서도록 선언 순서를 정했다.
         */
        public enum Kind {
            PUBLISHED,
            CONSUMED
        }
    }
}
