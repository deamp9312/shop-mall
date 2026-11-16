package com.example.shop.payment;

/** payments.status와 그 전이 테이블 (REQ-DA-001). */
public enum PaymentStatus {
    READY,
    APPROVED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case READY -> target == APPROVED || target == FAILED || target == CANCELLED;
            case APPROVED -> target == CANCELLED;
            case FAILED, CANCELLED -> false;
        };
    }
}
