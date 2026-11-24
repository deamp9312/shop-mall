package com.example.shop.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * 모든 Kafka 메시지의 JSON envelope이다 (REQ-IF-002 3). occurredAt은 ISO-8601 instant이고 aggregateId는 orderId이다.
 * <pre>
 * [Kafka에 실제로 들어가는 record 모양] (예: 주문 7번 생성)
 *   topic   = shop.order.created
 *   key     = "7"                                  ← orderId. 같은 주문은 항상 같은 partition으로 간다
 *   headers = traceId=f3a9c1d2e4b5, eventId=9b1deb4d-..., eventType=ORDER_CREATED
 *   value   = (아래 JSON 문자열)
 *   {
 *     "eventId":     "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",  ← 중복 처리 방지 키 (UUID)
 *     "eventType":   "ORDER_CREATED",
 *     "version":     1,
 *     "occurredAt":  "2026-11-24T01:00:00.123Z",
 *     "aggregateId": 7,                                       ← orderId
 *     "traceId":     "f3a9c1d2e4b5",                          ← HTTP 요청부터 이어지는 추적 id
 *     "payload":     {"orderId":7,"memberId":1,"items":[{"productId":3,"qty":2,"price":8900}],
 *                     "totalAmount":17800,"expiresAt":"2026-11-24T10:15:00"}   ← 이벤트별로 다름 (XxxEvent 클래스)
 *   }
 * </pre>
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
