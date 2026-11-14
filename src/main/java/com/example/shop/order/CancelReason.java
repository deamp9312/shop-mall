package com.example.shop.order;

public enum CancelReason {
    /**
     * Payment deadline passed (REQ-F-010).
     *
     * 결제 기한이 지났다 (REQ-F-010).
     */
    PAYMENT_TIMEOUT,
    /**
     * Cancelled by the member (REQ-F-011).
     *
     * 회원이 취소했다 (REQ-F-011).
     */
    USER_CANCEL
}
