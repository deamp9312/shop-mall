package com.example.shop.redisstudy.step1;

import com.example.shop.redisstudy.StudyRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * STEP 1. String 과 TTL — Redis의 가장 기본.
 * <p>
 * 핵심:
 * <ul>
 *   <li>Redis는 메모리에 있는 거대한 Map(key → value)이다. 가장 기본 값 타입이 String</li>
 *   <li>키마다 TTL(만료 시간)을 줄 수 있고, 시간이 지나면 Redis가 알아서 지운다 → 캐시, 세션, 임시 잠금에 딱 맞다</li>
 *   <li>Lettuce의 메서드 이름은 Redis 명령어와 같다: redis.set(...) == SET, redis.get(...) == GET</li>
 * </ul>
 * <pre>
 * [Redis 명령]                                    [결과]
 * SET study:step1:greeting "hello"               → OK
 * GET study:step1:greeting                       → "hello"
 * TTL study:step1:greeting                       → -1   (만료 없음)
 * SET study:step1:temp "잠깐만 있을 값" EX 2      → OK   (2초 뒤 자동 삭제)
 * TTL study:step1:temp                           → 2
 * (2.5초 대기)
 * GET study:step1:temp                           → nil  (만료되어 사라짐)
 * EXISTS study:step1:greeting                    → 1
 * DEL study:step1:greeting                       → 1    (지운 키 개수)
 * GET study:step1:greeting                       → nil
 * </pre>
 * 쇼핑몰: 로그인 실패 카운터 shop:login:fail:{email} TTL 5분, 멱등성 키 TTL 24시간 (common/RedisKeys.java 목록 참고)
 * <p>
 * 해볼 것: redis-cli 에서 MONITOR 를 켜 두고 실행해 보기 (README 참고) → 이 코드가 보내는 명령이 실시간으로 보인다.
 */
public class Step1StringAndTtl {

    public static void main(String[] args) throws Exception {
        RedisClient client = StudyRedis.newClient();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync(); // sync = 응답이 올 때까지 기다리는 API
            StudyRedis.deleteKeys(redis, "study:step1:*");

            print("SET study:step1:greeting \"hello\"", redis.set("study:step1:greeting", "hello"));
            print("GET study:step1:greeting", redis.get("study:step1:greeting"));
            print("TTL study:step1:greeting", redis.ttl("study:step1:greeting"));

            print("SET study:step1:temp \"잠깐만 있을 값\" EX 2",
                    redis.set("study:step1:temp", "잠깐만 있을 값", SetArgs.Builder.ex(2)));
            print("TTL study:step1:temp", redis.ttl("study:step1:temp"));
            System.out.println("(2.5초 대기)");
            Thread.sleep(2_500);
            print("GET study:step1:temp", redis.get("study:step1:temp"));

            print("EXISTS study:step1:greeting", redis.exists("study:step1:greeting"));
            print("DEL study:step1:greeting", redis.del("study:step1:greeting"));
            print("GET study:step1:greeting", redis.get("study:step1:greeting"));
        } finally {
            client.shutdown();
        }
    }

    private static void print(String command, Object result) {
        System.out.printf("%-46s → %s%n", command, result == null ? "nil" : result);
    }
}
