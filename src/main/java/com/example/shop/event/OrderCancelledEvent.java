package com.example.shop.event;

/** order -> payment, stock (REQ-F-010, REQ-F-011). reason is a CancelReason name. */
public record OrderCancelledEvent(long orderId, long memberId, String reason) implements DomainEvent {

    @Override
    public EventType type() {
        return EventType.ORDER_CANCELLED;
    }
}
