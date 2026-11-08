package com.example.shop.event;

import java.time.LocalDateTime;

/**
 * An outbox row that gave up (REQ-DA-002) as shown on the event screen (REQ-F-014). heldBack is the number of later
 * PENDING rows of the same order that wait for it.
 */
public record FailedOutboxEvent(long id, String eventId, String topic, String messageKey, int retryCount,
                                LocalDateTime createdAt, long heldBack) {
}
