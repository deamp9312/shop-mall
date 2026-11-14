package com.example.shop.event;

/**
 * order -> payment: a payment succeeded for an order that can no longer be paid (REQ-F-009 3).
 *
 * order -> payment: 더 이상 결제할 수 없는 주문에 결제가 성공했다 (REQ-F-009 3).
 */
public record PaymentCancelRequestedEvent(long orderId, String reason) implements DomainEvent {

    @Override
    public EventType type() {
        return EventType.PAYMENT_CANCEL_REQUESTED;
    }
}
