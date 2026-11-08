package com.example.shop.payment;

/** Display-only payment method of the mock PG; no card or account number is ever collected (REQ-F-008). */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER
}
