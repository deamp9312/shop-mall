package com.example.shop.event;

import com.example.shop.common.TraceIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way to publish an event (REQ-DA-002): it is stored with the caller's domain change and sent by
 * {@link OutboxRelay} after the commit. A Kafka outage therefore never rolls back the domain change (REQ-F-006).
 */
@Component
public class EventOutbox {

    private static final Logger log = LoggerFactory.getLogger(EventOutbox.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public EventOutbox(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /** Must join the caller's transaction, otherwise the event could be stored without the change or vice versa. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event, TraceIds.currentOrNew());
        outbox.save(new OutboxEvent(envelope.eventId(), event.type().topic(), String.valueOf(event.orderId()),
                toJson(envelope)));
        log.info("event recorded in outbox: type={} eventId={}", envelope.eventType(), envelope.eventId());
    }

    /**
     * Sends a dead-lettered envelope again, unchanged and with its original eventId (REQ-F-014 2). Consumer groups that
     * already handled it skip it by eventId, so only the group that failed handles it. The original outbox row is
     * re-queued while it is still kept (event_id is unique); otherwise a new row is stored.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void republish(String eventId, String topic, String messageKey, String envelopeJson) {
        outbox.findByEventId(eventId).ifPresentOrElse(OutboxEvent::requeue,
                () -> outbox.save(new OutboxEvent(eventId, topic, messageKey, envelopeJson)));
        log.info("event re-queued for publishing: topic={} eventId={}", topic, eventId);
    }

    private String toJson(EventEnvelope<DomainEvent> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize event " + envelope.eventType(), e);
        }
    }
}
