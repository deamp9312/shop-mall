package com.example.shop.common;

/**
 * A status change that the transition table does not allow (REQ-DA-001).
 * Not retryable: a Kafka consumer that hits this sends the record straight to the DLT (REQ-NF-002).
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String aggregate, Long id, Enum<?> from, Enum<?> to) {
        super(aggregate + " " + id + ": transition " + from + " -> " + to + " is not allowed");
    }
}
