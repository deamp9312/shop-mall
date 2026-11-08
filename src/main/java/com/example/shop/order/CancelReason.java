package com.example.shop.order;

public enum CancelReason {
    /** Payment deadline passed (REQ-F-010). */
    PAYMENT_TIMEOUT,
    /** Cancelled by the member (REQ-F-011). */
    USER_CANCEL
}
