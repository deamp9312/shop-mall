package com.example.shop.common;

import java.util.Locale;

/**
 * Redis 키를 만드는 유일한 곳이다 (REQ-IF-003). 다른 클래스에는 "shop:" 리터럴이 있으면 안 된다.
 *
 * <pre>
 * shop:session:*                     Spring Session, TTL 30m        (REQ-F-002)
 * shop:product:{id}                  상품 캐시 JSON, TTL 10m        (REQ-F-004)
 * shop:cart:{memberId}               장바구니 Hash, TTL 7d          (REQ-F-005)
 * shop:stock:{productId}             가용 재고, TTL 없음            (REQ-F-007)
 * shop:stock-sync:epoch              재동기화 카운터, TTL 없음      (REQ-F-007 6, review Medium-2 확장)
 * shop:idem:{scope}:{key}            멱등성 키, TTL 24h             (REQ-SE-002)
 * shop:lock:{name}                   분산 락, TTL &lt; 작업 시간    (REQ-F-010, REQ-F-013)
 * shop:event:done:{group}:{eventId}  컨슈머 중복 처리 보조, TTL 24h (REQ-NF-002, 기준 데이터는 DB)
 * shop:login:fail:{email}            로그인 실패 카운터, TTL 5m     (REQ-SE-001 4, 2단계 계약 확장)
 * </pre>
 * 키 조회는 KEYS가 아니라 SCAN과 위 패턴으로 한다.
 */
public final class RedisKeys {

    private static final String PREFIX = "shop:";

    public static final String SESSION_NAMESPACE = PREFIX + "session";
    /** Spring Cache가 캐시 키(상품 id) 앞에 붙이는 값이며, 결과는 {@link #product(long)}와 같다. */
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
     * 재동기화는 카운터를 쓰기 전에 매번 이 값을 올린다. 보상 대상 Redis 변경 이후 이 값이 바뀌었으면 롤백 보상을
     * 건너뛴다. 그 재동기화가 이미 롤백된 DB를 읽었기 때문이다.
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
