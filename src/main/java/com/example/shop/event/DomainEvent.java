package com.example.shop.event;

/**
 * Payload of one event. Every event belongs to an order: {@link #orderId()} is the envelope's aggregateId and the
 * Kafka message key, so all events of one order stay in one partition (REQ-IF-002 2). Payloads carry no personal data
 * such as address or phone number.
 */
public interface DomainEvent {

    /** Not a Jackson property (no "get" prefix), so it is not written into the payload. */
    EventType type();

    long orderId();
}
