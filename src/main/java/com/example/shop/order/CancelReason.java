package com.example.shop.order;

public enum CancelReason {
    /** 결제 기한이 지났다 (REQ-F-010). */
    PAYMENT_TIMEOUT,
    /** 회원이 직접 취소했다 (REQ-F-011). */
    USER_CANCEL
}
