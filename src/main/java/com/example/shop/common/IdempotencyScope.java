package com.example.shop.common;

import java.util.Locale;

/** Scopes of the idempotency key built by RedisKeys.idempotency (REQ-SE-002). */
public enum IdempotencyScope {
    ORDER,
    PAYMENT;

    String keySegment() {
        return name().toLowerCase(Locale.ROOT);
    }
}
