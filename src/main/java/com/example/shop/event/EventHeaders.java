package com.example.shop.event;

/**
 * Kafka record headers written by the outbox relay. traceId is restored into the consumer MDC (REQ-NF-003 1).
 *
 * 아웃박스 릴레이가 쓰는 Kafka 레코드 헤더. traceId는 컨슈머 MDC로 복원된다 (REQ-NF-003 1).
 */
public final class EventHeaders {

    public static final String TRACE_ID = "traceId";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";

    private EventHeaders() {
    }
}
