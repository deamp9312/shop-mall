package com.example.shop.event;

/** payment -> order, notification (REQ-F-011 3). */
public record PaymentCancelledEvent(long orderId, long paymentId) implements DomainEvent {

    @Override
    public EventType type() {
        return EventType.PAYMENT_CANCELLED;
    }
}
