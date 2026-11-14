package com.example.shop.common;

import java.util.Locale;

/**
 * Scopes of the idempotency key built by RedisKeys.idempotency (REQ-SE-002).
 *
 * RedisKeys.idempotency가 만드는 멱등 키의 범위 (REQ-SE-002).
 */
public enum IdempotencyScope {
    ORDER,
    PAYMENT;

    String keySegment() {
        return name().toLowerCase(Locale.ROOT);
    }
}
