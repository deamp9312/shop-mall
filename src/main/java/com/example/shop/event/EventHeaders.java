package com.example.shop.event;

/** outbox relay가 기록하는 Kafka record 헤더이다. traceId는 consumer MDC로 복원된다 (REQ-NF-003 1). */
public final class EventHeaders {

    public static final String TRACE_ID = "traceId";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";

    private EventHeaders() {
    }
}
