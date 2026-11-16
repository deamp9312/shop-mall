package com.example.shop.event;

/** event envelope로 읽을 수 없는 record이다. 재시도 대상이 아니며 곧바로 DLT로 보낸다 (REQ-NF-002 3). */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String topic, Throwable cause) {
        super("malformed event on " + topic, cause);
    }

    public MalformedEventException(String topic) {
        super("event envelope on " + topic + " misses eventId, eventType, aggregateId or payload");
    }
}
