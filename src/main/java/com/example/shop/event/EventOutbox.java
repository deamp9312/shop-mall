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
 * 이벤트를 발행하는 유일한 경로이다 (REQ-DA-002). 이벤트는 호출자의 도메인 변경과 함께 저장되고, commit 후
 * {@link OutboxRelay}가 발행한다. 따라서 Kafka 장애가 도메인 변경을 롤백시키는 일은 없다 (REQ-F-006).
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

    /** 반드시 호출자의 트랜잭션에 참여해야 한다. 그렇지 않으면 변경 없이 이벤트만 저장되거나 그 반대가 될 수 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(DomainEvent event) {
        EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event, TraceIds.currentOrNew());
        outbox.save(new OutboxEvent(envelope.eventId(), event.type().topic(), String.valueOf(event.orderId()),
                toJson(envelope)));
        log.info("event recorded in outbox: type={} eventId={}", envelope.eventType(), envelope.eventId());
    }

    /**
     * dead letter로 간 envelope를 원래 eventId 그대로, 변경 없이 다시 발행한다 (REQ-F-014 2). 이미 처리한 consumer
     * group은 eventId로 건너뛰므로 실패했던 group만 처리한다. 원래 outbox 행이 아직 남아 있으면 그 행을 재등록하고
     * (event_id는 unique이다), 없으면 새 행을 저장한다.
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
