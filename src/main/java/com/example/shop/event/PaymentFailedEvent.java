package com.example.shop.event;

/**
 * payment -> order, stock, notification (REQ-F-009).
 *
 * payment -> order, stock, notification (REQ-F-009).
 */
public record PaymentFailedEvent(long orderId, long paymentId) implements DomainEvent {

    @Override
    public EventType type() {
        return EventType.PAYMENT_FAILED;
    }
}
