package com.example.shop.common;

import java.util.Locale;

/** RedisKeys.idempotency가 만드는 멱등성 키의 범위 (REQ-SE-002). */
public enum IdempotencyScope {
    ORDER,
    PAYMENT;

    String keySegment() {
        return name().toLowerCase(Locale.ROOT);
    }
}
