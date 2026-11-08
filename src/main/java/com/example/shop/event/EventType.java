package com.example.shop.event;

/**
 * Event types with their topic and payload schema version (REQ-IF-002). The name is written to the envelope's
 * eventType and to order_status_history.event_type. Bump the version when a payload changes incompatibly.
 */
public enum EventType {
    ORDER_CREATED(EventTopics.ORDER_CREATED),
    ORDER_CANCELLED(EventTopics.ORDER_CANCELLED),
    PAYMENT_COMPLETED(EventTopics.PAYMENT_COMPLETED),
    PAYMENT_FAILED(EventTopics.PAYMENT_FAILED),
    PAYMENT_CANCELLED(EventTopics.PAYMENT_CANCELLED),
    PAYMENT_CANCEL_REQUESTED(EventTopics.PAYMENT_CANCEL_REQUESTED);

    private static final int FIRST_VERSION = 1;

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }

    public int version() {
        return FIRST_VERSION;
    }
}
