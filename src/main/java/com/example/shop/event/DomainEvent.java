package com.example.shop.event;

/**
 * Payload of one event. Every event belongs to an order: {@link #orderId()} is the envelope's aggregateId and the
 * Kafka message key, so all events of one order stay in one partition (REQ-IF-002 2). Payloads carry no personal data
 * such as address or phone number.
 *
 * 이벤트 하나의 payload. 모든 이벤트는 주문에 속한다. {@link #orderId()}가 envelope의 aggregateId이자 Kafka 메시지
 * 키이므로, 한 주문의 이벤트는 모두 같은 파티션에 머문다 (REQ-IF-002 2). payload에는 주소나 전화번호 같은 개인정보를
 * 넣지 않는다.
 */
public interface DomainEvent {

    /**
     * Not a Jackson property (no "get" prefix), so it is not written into the payload.
     *
     * Jackson 프로퍼티가 아니므로("get" 접두어 없음) payload에 쓰이지 않는다.
     */
    EventType type();

    long orderId();
}
