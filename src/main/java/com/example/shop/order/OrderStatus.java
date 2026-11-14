package com.example.shop.order;

/**
 * orders.status and its transition table (REQ-DA-001).
 *
 * orders.status와 그 전이표 (REQ-DA-001).
 */
public enum OrderStatus {
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PAYMENT_PENDING -> target == PAID || target == PAYMENT_FAILED || target == CANCELLED;
            case PAID -> target == CANCELLED;
            case PAYMENT_FAILED, CANCELLED -> false;
        };
    }
}
