package com.example.shop.stock;

/**
 * RESERVED -> CONFIRMED | RELEASED, CONFIRMED -> RELEASED (a paid order cancelled, REQ-F-011 4).
 * The conditional update on this status makes confirm/release run once.
 *
 * RESERVED -> CONFIRMED | RELEASED, CONFIRMED -> RELEASED (결제된 주문 취소, REQ-F-011 4).
 * 이 상태에 거는 조건부 update로 확정/해제가 한 번만 실행된다.
 */
public enum StockReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
