package com.example.shop.common;

import java.util.Locale;

/**
 * The only place that builds Redis keys (REQ-IF-003). No other class may contain a "shop:" literal.
 *
 * <pre>
 * shop:session:*                     Spring Session, TTL 30m           (REQ-F-002)
 * shop:product:{id}                  product cache JSON, TTL 10m       (REQ-F-004)
 * shop:cart:{memberId}               cart Hash, TTL 7d                 (REQ-F-005)
 * shop:stock:{productId}             available stock, no TTL           (REQ-F-007)
 * shop:stock-sync:epoch              resync counter, no TTL            (REQ-F-007 6, review Medium-2 extension)
 * shop:idem:{scope}:{key}            idempotency key, TTL 24h          (REQ-SE-002)
 * shop:lock:{name}                   distributed lock, TTL &lt; job time (REQ-F-010, REQ-F-013)
 * shop:event:done:{group}:{eventId}  consumer dedup helper, TTL 24h    (REQ-NF-002, DB is the source of truth)
 * shop:login:fail:{email}            failed login counter, TTL 5m      (REQ-SE-001 4, contract extension in step 2)
 * </pre>
 * Keys are enumerated with SCAN and these patterns, never with KEYS.
 */
public final class RedisKeys {

    private static final String PREFIX = "shop:";

    public static final String SESSION_NAMESPACE = PREFIX + "session";
    /** Spring Cache prepends this to the cache key (the product id), giving {@link #product(long)}. */
    public static final String PRODUCT_CACHE_PREFIX = PREFIX + "product:";
    public static final String STOCK_SCAN_PATTERN = PREFIX + "stock:*";

    private RedisKeys() {
    }

    public static String product(long productId) {
        return PRODUCT_CACHE_PREFIX + productId;
    }

    public static String cart(long memberId) {
        return PREFIX + "cart:" + memberId;
    }

    public static String stock(long productId) {
        return PREFIX + "stock:" + productId;
    }

    /**
     * Raised by every resync before it writes the counters. A rollback compensation is skipped when it changed since
     * the compensated Redis change, because that resync already read the rolled-back database.
     */
    public static String stockSyncEpoch() {
        return PREFIX + "stock-sync:epoch";
    }

    public static String idempotency(IdempotencyScope scope, String key) {
        return PREFIX + "idem:" + scope.keySegment() + ":" + key;
    }

    public static String lock(String name) {
        return PREFIX + "lock:" + name;
    }

    public static String eventDone(String consumerGroup, String eventId) {
        return PREFIX + "event:done:" + consumerGroup + ":" + eventId;
    }

    public static String loginFailure(String email) {
        return PREFIX + "login:fail:" + email.toLowerCase(Locale.ROOT);
    }
}
