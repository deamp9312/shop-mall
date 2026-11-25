package com.example.shop.redisstudy.step2;

import com.example.shop.redisstudy.StudyRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * STEP 2. 원자적 카운터 INCR — "읽고 → 더하고 → 쓰기"는 동시성에 깨진다.
 * <p>
 * 핵심: Redis는 명령 하나하나를 <b>한 줄로 세워 하나씩</b> 실행한다(싱글 스레드). 그래서 INCR 한 명령은 절대 끼어들기를 당하지 않는다.
 * 하지만 GET 과 SET 을 따로 보내면, 그 사이에 다른 클라이언트의 명령이 끼어들 수 있다.
 * <pre>
 * [IN]  스레드 10개가 각자 100번씩 +1 (기대값 1000)
 *
 * ① GET → +1 → SET (잘못된 방법)
 *    스레드 A: GET → "5"          스레드 B: GET → "5"      ← 둘 다 5를 읽음
 *    스레드 A: SET "6"            스레드 B: SET "6"        ← 하나가 덮어써서 +1이 사라짐 (lost update)
 * [OUT] GET+SET 결과 = 437   (실행마다 다름, 1000보다 작다)
 *
 * ② INCR (올바른 방법)
 *    INCR study:step2:incr → 1, 2, 3 ... (Redis 안에서 읽기+더하기+쓰기를 한 번에)
 * [OUT] INCR 결과 = 1000
 * </pre>
 * 쇼핑몰: 로그인 실패 횟수 INCR (scripts/login-failure.lua), 재고 복구 INCRBY (scripts/stock-add.lua)
 */
public class Step2AtomicCounter {

    private static final int THREADS = 10;
    private static final int PER_THREAD = 100;

    public static void main(String[] args) throws Exception {
        RedisClient client = StudyRedis.newClient();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync(); // Lettuce 연결은 여러 스레드가 같이 써도 안전하다
            StudyRedis.deleteKeys(redis, "study:step2:*");

            runConcurrently(() -> {
                String current = redis.get("study:step2:get-set");             // 1) 읽고
                long next = (current == null ? 0 : Long.parseLong(current)) + 1; // 2) 더하고
                redis.set("study:step2:get-set", String.valueOf(next));         // 3) 쓰기 — 그 사이 다른 스레드가 끼어듦
            });
            System.out.println("① GET+SET 결과 = " + redis.get("study:step2:get-set") + "  (기대값 1000)");

            runConcurrently(() -> redis.incr("study:step2:incr"));
            System.out.println("② INCR 결과    = " + redis.get("study:step2:incr") + "  (기대값 1000)");
        } finally {
            client.shutdown();
        }
    }

    private static void runConcurrently(Runnable increment) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    increment.run();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
    }
}
