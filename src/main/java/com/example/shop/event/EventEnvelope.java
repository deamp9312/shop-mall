package com.example.shop.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * 모든 Kafka 메시지의 JSON envelope이다 (REQ-IF-002 3). occurredAt은 ISO-8601 instant이고 aggregateId는 orderId이다.
 */
public record EventEnvelope<P>(String eventId, String eventType, int version,
                               @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt, Long aggregateId,
                               String traceId, P payload) {

    public static <P extends DomainEvent> EventEnvelope<P> wrap(P event, String traceId) {
        EventType type = event.type();
        return new EventEnvelope<>(UUID.randomUUID().toString(), type.name(), type.version(), Instant.now(),
                event.orderId(), traceId, event);
    }

    boolean hasRequiredFields() {
        return eventId != null && eventType != null && aggregateId != null && payload != null;
    }
}
