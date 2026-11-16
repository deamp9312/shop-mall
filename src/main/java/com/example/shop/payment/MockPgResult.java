package com.example.shop.payment;

/** mock 결제 화면에서 사용자가 고르는 결과 (REQ-F-008 2). */
public enum MockPgResult {
    SUCCESS,
    FAIL,
    /** mock PG가 늦게 응답한 뒤 승인한다. 그동안 화면은 "결제 처리 중"을 유지한다. */
    DELAY
}
