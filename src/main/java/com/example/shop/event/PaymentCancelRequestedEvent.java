package com.example.shop.event;

/** order -> payment: a payment succeeded for an order that can no longer be paid (REQ-F-009 3). */
public record PaymentCancelRequestedEvent(long orderId, String reason) implements DomainEvent {

    @Override
    public EventType type() {
        return EventType.PAYMENT_CANCEL_REQUESTED;
    }
}
