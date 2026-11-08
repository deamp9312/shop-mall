package com.example.shop.event;

import java.time.LocalDateTime;

/** payment -> order, stock, notification (REQ-F-009). */
public record PaymentCompletedEvent(long orderId, long paymentId, long amount, LocalDateTime approvedAt)
        implements DomainEvent {

    @Override
    public EventType type() {
        return EventType.PAYMENT_COMPLETED;
    }
}
