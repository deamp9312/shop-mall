package com.example.shop.common;

/**
 * 전이 표가 허용하지 않는 상태 변경이다 (REQ-DA-001).
 * 재시도 대상이 아니다. 이 예외를 만난 Kafka 컨슈머는 레코드를 곧바로 DLT로 보낸다 (REQ-NF-002).
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String aggregate, Long id, Enum<?> from, Enum<?> to) {
        super(aggregate + " " + id + ": transition " + from + " -> " + to + " is not allowed");
    }
}
