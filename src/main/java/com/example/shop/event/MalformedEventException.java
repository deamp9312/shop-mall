package com.example.shop.event;

/**
 * A record that cannot be read as an event envelope. Not retryable: it goes straight to the DLT (REQ-NF-002 3).
 *
 * 이벤트 envelope으로 읽을 수 없는 레코드. 재시도 대상이 아니며 바로 DLT로 간다 (REQ-NF-002 3).
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String topic, Throwable cause) {
        super("malformed event on " + topic, cause);
    }

    public MalformedEventException(String topic) {
        super("event envelope on " + topic + " misses eventId, eventType, aggregateId or payload");
    }
}
