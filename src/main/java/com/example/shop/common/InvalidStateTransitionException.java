package com.example.shop.common;

/**
 * A status change that the transition table does not allow (REQ-DA-001).
 * Not retryable: a Kafka consumer that hits this sends the record straight to the DLT (REQ-NF-002).
 *
 * 상태 전이표가 허용하지 않는 상태 변경 (REQ-DA-001).
 * 재시도 대상이 아니다. 이 예외를 만난 Kafka 컨슈머는 레코드를 바로 DLT로 보낸다 (REQ-NF-002).
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String aggregate, Long id, Enum<?> from, Enum<?> to) {
        super(aggregate + " " + id + ": transition " + from + " -> " + to + " is not allowed");
    }
}
