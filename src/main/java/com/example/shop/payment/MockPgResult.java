package com.example.shop.payment;

/**
 * The outcome the user picks on the mock payment screen (REQ-F-008 2).
 *
 * 사용자가 모의 결제 화면에서 고르는 결과 (REQ-F-008 2).
 */
public enum MockPgResult {
    SUCCESS,
    FAIL,
    /**
     * The mock PG answers late and then approves; the screen keeps "결제 처리 중" meanwhile.
     *
     * 모의 PG가 늦게 응답한 뒤 승인한다. 그동안 화면은 "결제 처리 중"을 유지한다.
     */
    DELAY
}
