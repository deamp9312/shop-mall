package com.example.shop.event;

/**
 * 이벤트 유형과 그 topic, payload 스키마 버전이다 (REQ-IF-002). 이름은 envelope의 eventType과
 * order_status_history.event_type에 기록된다. payload가 호환되지 않게 바뀌면 버전을 올린다.
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
