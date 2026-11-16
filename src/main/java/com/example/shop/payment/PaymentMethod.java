package com.example.shop.payment;

/** mock PG의 표시용 결제 수단. 카드 번호나 계좌 번호는 절대 수집하지 않는다 (REQ-F-008). */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER
}
