package com.example.shop.redisstudy.step3;

import com.example.shop.redisstudy.StudyRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

/**
 * STEP 3. Hash — 키 하나 안에 여러 필드 (장바구니).
 * <p>
 * 핵심: Hash는 "키 안의 작은 Map"이다. 장바구니처럼 <b>일부만 자주 바뀌는</b> 데이터에 좋다.
 * String에 JSON 통째로 넣으면 수량 하나 바꿀 때도 전체를 읽고-고치고-다시 써야 하고(STEP 2의 lost update 위험),
 * Hash는 필드 하나만 HSET / HINCRBY 로 원자적으로 바꾼다.
 * <pre>
 * 키: study:step3:cart:1  (회원 1의 장바구니)   필드 = productId, 값 = 수량
 *
 * [Redis 명령]                                [결과]
 * HSET study:step3:cart:1 3 2                → true   (상품 3을 2개 담음. 새 필드면 true, redis-cli에선 1)
 * HSET study:step3:cart:1 5 1                → true
 * HINCRBY study:step3:cart:1 3 1             → 3      (상품 3 하나 더 → 3개)
 * HGET study:step3:cart:1 3                  → "3"
 * HGETALL study:step3:cart:1                 → {3=3, 5=1}
 * HLEN study:step3:cart:1                    → 2      (상품 종류 수)
 * HDEL study:step3:cart:1 5                  → 1      (상품 5 빼기)
 * EXPIRE study:step3:cart:1 604800           → true   (TTL 7일. TTL은 필드가 아니라 키 전체에 걸린다)
 * TTL study:step3:cart:1                     → 604800
 * HGETALL study:step3:cart:1                 → {3=3}
 * </pre>
 * 쇼핑몰: cart/CartService.java (shop:cart:{memberId}), scripts/cart-add.lua
 */
public class Step3HashCart {

    private static final String CART = "study:step3:cart:1";

    public static void main(String[] args) {
        RedisClient client = StudyRedis.newClient();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync();
            StudyRedis.deleteKeys(redis, "study:step3:*");

            print("HSET " + CART + " 3 2", redis.hset(CART, "3", "2"));
            print("HSET " + CART + " 5 1", redis.hset(CART, "5", "1"));
            print("HINCRBY " + CART + " 3 1", redis.hincrby(CART, "3", 1));
            print("HGET " + CART + " 3", redis.hget(CART, "3"));
            print("HGETALL " + CART, redis.hgetall(CART));
            print("HLEN " + CART, redis.hlen(CART));
            print("HDEL " + CART + " 5", redis.hdel(CART, "5"));
            print("EXPIRE " + CART + " 604800", redis.expire(CART, Duration.ofDays(7)));
            print("TTL " + CART, redis.ttl(CART));
            print("HGETALL " + CART, redis.hgetall(CART));
        } finally {
            client.shutdown();
        }
    }

    private static void print(String command, Object result) {
        System.out.printf("%-42s → %s%n", command, result);
    }
}
