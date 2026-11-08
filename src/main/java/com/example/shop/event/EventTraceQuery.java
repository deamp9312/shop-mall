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
     */
    public record EventTraceEntry(LocalDateTime at, Kind kind, String topic, String eventId, String consumerGroup,
                                  String result) {

        public static final String PROCESSED = "PROCESSED";

        /** Declared in the order a publish precedes its consumption when both have the same timestamp. */
        public enum Kind {
            PUBLISHED,
            CONSUMED
        }
    }
}
