package com.example.shop.payment;

/**
 * Display-only payment method of the mock PG; no card or account number is ever collected (REQ-F-008).
 *
 * 모의 PG의 표시용 결제 수단. 카드 번호나 계좌 번호는 절대 받지 않는다 (REQ-F-008).
 */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER
}
