package com.example.shop.payment;

/** The outcome the user picks on the mock payment screen (REQ-F-008 2). */
public enum MockPgResult {
    SUCCESS,
    FAIL,
    /** The mock PG answers late and then approves; the screen keeps "결제 처리 중" meanwhile. */
    DELAY
}
