package com.example.shop.redisstudy.step6;

import com.example.shop.redisstudy.StudyRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * STEP 6. 다른 자료구조 — List / Set / Sorted Set.
 * <p>
 * 쇼핑몰은 String과 Hash만 쓰지만, 실무에서 자주 만나는 나머지 세 가지를 쇼핑몰 기능으로 비유해 본다.
 * <pre>
 * ── List: 순서 있는 목록 (최근 본 상품) ──
 * LPUSH study:step6:recent:1 3 → 1         (앞에 추가)
 * LPUSH study:step6:recent:1 5 → 2
 * LPUSH study:step6:recent:1 9 → 3
 * LTRIM study:step6:recent:1 0 1 → OK      (최근 2개만 남기기)
 * LRANGE study:step6:recent:1 0 -1 → [9, 5]
 *
 * ── Set: 중복 없는 집합 (상품 3에 '좋아요' 누른 회원) ──
 * SADD study:step6:likes:3 1 → 1
 * SADD study:step6:likes:3 2 → 1
 * SADD study:step6:likes:3 1 → 0           (이미 있음 → 중복 저장 안 됨)
 * SISMEMBER study:step6:likes:3 2 → true    (회원 2가 눌렀나?)
 * SCARD study:step6:likes:3 → 2             (좋아요 수)
 *
 * ── Sorted Set: 점수로 정렬된 집합 (판매량 랭킹) ──
 * ZINCRBY study:step6:ranking 2 product-3 → 2.0   (상품 3이 2개 팔림)
 * ZINCRBY study:step6:ranking 5 product-5 → 5.0
 * ZINCRBY study:step6:ranking 1 product-9 → 1.0
 * ZINCRBY study:step6:ranking 4 product-3 → 6.0   (누적)
 * ZREVRANGE study:step6:ranking 0 2 WITHSCORES → [product-3=6.0, product-5=5.0, product-9=1.0]  (1~3위)
 * </pre>
 */
public class Step6DataStructures {

    public static void main(String[] args) {
        RedisClient client = StudyRedis.newClient();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync();
            StudyRedis.deleteKeys(redis, "study:step6:*");

            System.out.println("── List: 최근 본 상품 ──");
            String recent = "study:step6:recent:1";
            print("LPUSH " + recent + " 3", redis.lpush(recent, "3"));
            print("LPUSH " + recent + " 5", redis.lpush(recent, "5"));
            print("LPUSH " + recent + " 9", redis.lpush(recent, "9"));
            print("LTRIM " + recent + " 0 1", redis.ltrim(recent, 0, 1));
            print("LRANGE " + recent + " 0 -1", redis.lrange(recent, 0, -1));

            System.out.println("── Set: 좋아요 누른 회원 ──");
            String likes = "study:step6:likes:3";
            print("SADD " + likes + " 1", redis.sadd(likes, "1"));
            print("SADD " + likes + " 2", redis.sadd(likes, "2"));
            print("SADD " + likes + " 1", redis.sadd(likes, "1"));
            print("SISMEMBER " + likes + " 2", redis.sismember(likes, "2"));
            print("SCARD " + likes, redis.scard(likes));

            System.out.println("── Sorted Set: 판매량 랭킹 ──");
            String ranking = "study:step6:ranking";
            print("ZINCRBY " + ranking + " 2 product-3", redis.zincrby(ranking, 2, "product-3"));
            print("ZINCRBY " + ranking + " 5 product-5", redis.zincrby(ranking, 5, "product-5"));
            print("ZINCRBY " + ranking + " 1 product-9", redis.zincrby(ranking, 1, "product-9"));
            print("ZINCRBY " + ranking + " 4 product-3", redis.zincrby(ranking, 4, "product-3"));
            print("ZREVRANGE " + ranking + " 0 2 WITHSCORES", redis.zrevrangeWithScores(ranking, 0, 2).stream()
                    .map(s -> s.getValue() + "=" + s.getScore()).toList());
        } finally {
            client.shutdown();
        }
    }

    private static void print(String command, Object result) {
        System.out.printf("%-46s → %s%n", command, result);
    }
}
