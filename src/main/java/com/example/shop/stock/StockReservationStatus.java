package com.example.shop.stock;

/**
 * RESERVED -> CONFIRMED | RELEASED, CONFIRMED -> RELEASED (a paid order cancelled, REQ-F-011 4).
 * The conditional update on this status makes confirm/release run once.
 */
public enum StockReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
