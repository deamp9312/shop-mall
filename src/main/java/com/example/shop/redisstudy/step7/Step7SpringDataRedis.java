package com.example.shop.redisstudy.step7;

import com.example.shop.redisstudy.StudyRedis;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * STEP 7. Spring Data Redis — StringRedisTemplate.
 * <p>
 * 핵심: 쇼핑몰 코드는 Lettuce를 직접 쓰지 않고 Spring의 {@link StringRedisTemplate}을 쓴다. STEP 1~5의 명령이 이렇게 바뀐다.
 * <pre>
 * Redis 명령              StringRedisTemplate                              쇼핑몰 사용처
 * ─────────────────────  ───────────────────────────────────────────────  ─────────────────────────
 * GET / SET              opsForValue().get(k) / set(k, v)                 AvailableStockReader
 * SET k v EX 86400       opsForValue().set(k, v, Duration.ofHours(24))    IdempotencyKeys.complete
 * SET k v NX EX          opsForValue().setIfAbsent(k, v, ttl)             IdempotencyKeys, RedisLock
 * INCR                   opsForValue().increment(k)                       AvailableStockSynchronizer
 * MGET                   opsForValue().multiGet(List.of(k1, k2))          AvailableStockReader
 * HGETALL / HDEL         opsForHash().entries(k) / delete(k, field)       CartService
 * EXPIRE / DEL           expire(k, ttl) / delete(k)                       CartService
 * EVAL                   execute(RedisScript, keys, args...)              CartService, AvailableStockCounter
 * </pre>
 * <pre>
 * [OUT] setIfAbsent(study:step7:idem:abc, IN_PROGRESS, 24h) → true
 *       setIfAbsent(study:step7:idem:abc, IN_PROGRESS, 24h) → false
 *       increment(study:step7:counter) x3                   → 3
 *       multiGet([study:step7:stock:3, study:step7:stock:5, study:step7:stock:9]) → [48, 0, null]
 *       opsForHash().put(study:step7:cart:1, 3, 2) / entries → {3=2, 5=1}
 *       execute(cart-add 스크립트, [study:step7:cart:1], 3, 1) → 3
 * </pre>
 * 쇼핑몰에서는 Spring Boot가 application.yml(spring.data.redis.*)로 StringRedisTemplate 빈을 자동으로 만들어 주입한다.
 */
public class Step7SpringDataRedis {

    /** 쇼핑몰 scripts/cart-add.lua 를 단순화한 것. KEYS[1] 장바구니, ARGV[1] 상품, ARGV[2] 추가 수량. 새 수량 반환. */
    private static final RedisScript<Long> CART_ADD = RedisScript.of("""
            local updated = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') + tonumber(ARGV[2])
            redis.call('HSET', KEYS[1], ARGV[1], updated)
            return updated
            """, Long.class);

    public static void main(String[] args) {
        LettuceConnectionFactory factory = StudyRedis.newConnectionFactory();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory); // key, value 모두 String으로 저장
            cleanup(redis);

            String idem = "study:step7:idem:abc";
            print("setIfAbsent(" + idem + ", IN_PROGRESS, 24h)",
                    redis.opsForValue().setIfAbsent(idem, "IN_PROGRESS", Duration.ofHours(24)));
            print("setIfAbsent(" + idem + ", IN_PROGRESS, 24h)",
                    redis.opsForValue().setIfAbsent(idem, "IN_PROGRESS", Duration.ofHours(24)));

            redis.opsForValue().increment("study:step7:counter");
            redis.opsForValue().increment("study:step7:counter");
            print("increment(study:step7:counter) x3", redis.opsForValue().increment("study:step7:counter"));

            redis.opsForValue().set("study:step7:stock:3", "48");
            redis.opsForValue().set("study:step7:stock:5", "0");
            List<String> keys = List.of("study:step7:stock:3", "study:step7:stock:5", "study:step7:stock:9");
            print("multiGet(" + keys + ")", redis.opsForValue().multiGet(keys));

            redis.opsForHash().put("study:step7:cart:1", "3", "2");
            redis.opsForHash().put("study:step7:cart:1", "5", "1");
            print("opsForHash().put(study:step7:cart:1, 3, 2) / entries", redis.opsForHash().entries("study:step7:cart:1"));

            print("execute(cart-add 스크립트, [study:step7:cart:1], 3, 1)",
                    redis.execute(CART_ADD, List.of("study:step7:cart:1"), "3", "1"));
        } finally {
            factory.destroy();
        }
    }

    private static void cleanup(StringRedisTemplate redis) {
        var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions().match("study:step7:*").build();
        try (var cursor = redis.scan(scanOptions)) { // KEYS 대신 SCAN
            cursor.forEachRemaining(redis::delete);
        }
    }

    private static void print(String call, Object result) {
        System.out.printf("%-56s → %s%n", call, result);
    }
}
