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
 *
 * 이벤트를 발행하는 유일한 방법 (REQ-DA-002). 이벤트는 호출자의 도메인 변경과 함께 저장되고, 커밋 후 {@link
 * OutboxRelay}가 보낸다. 따라서 Kafka 장애가 도메인 변경을 롤백시키는 일은 없다 (REQ-F-006).
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

    /**
     * Must join the caller's transaction, otherwise the event could be stored without the change or vice versa.
     *
     * 호출자의 트랜잭션에 참여해야 한다. 그렇지 않으면 변경 없이 이벤트만 저장되거나 그 반대가 될 수 있다.
     */
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
     *
     * DLT로 간 envelope을 원래 eventId 그대로, 바꾸지 않고 다시 보낸다 (REQ-F-014 2). 이미 처리한 컨슈머 그룹은
     * eventId로 건너뛰므로 실패했던 그룹만 처리한다. 원래 아웃박스 행이 아직 남아 있으면 그 행을 다시 대기열에
     * 넣고(event_id는 유일), 없으면 새 행을 저장한다.
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
