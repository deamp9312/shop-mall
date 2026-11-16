package com.example.shop.stock;

/**
 * RESERVED -> CONFIRMED | RELEASED, CONFIRMED -> RELEASED (결제 완료 주문 취소, REQ-F-011 4).
 * 이 상태에 대한 조건부 update로 확정/해제가 한 번만 실행된다.
 */
public enum StockReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
