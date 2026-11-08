package com.example.shop.event;

/** Kafka record headers written by the outbox relay. traceId is restored into the consumer MDC (REQ-NF-003 1). */
public final class EventHeaders {

    public static final String TRACE_ID = "traceId";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";

    private EventHeaders() {
    }
}
