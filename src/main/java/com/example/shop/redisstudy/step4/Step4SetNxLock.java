package com.example.shop.redisstudy.step4;

import com.example.shop.redisstudy.StudyRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * STEP 4. SET NX — "먼저 온 사람만" (중복 요청 방지, 분산 락).
 * <p>
 * 핵심: {@code SET key value NX} 는 키가 <b>없을 때만</b> 저장한다. 여러 서버가 동시에 시도해도 딱 한 명만 성공한다.
 * <pre>
 * ── ① 멱등성 키: 같은 주문 요청이 두 번 와도 주문은 한 번만 ──
 * SET study:step4:idem:abc "IN_PROGRESS" NX EX 86400  → OK    (첫 요청: 처리 시작)
 * SET study:step4:idem:abc "IN_PROGRESS" NX EX 86400  → nil   (두 번째 요청: 이미 누가 처리 중/완료)
 * SET study:step4:idem:abc "order-7" EX 86400         → OK    (첫 요청 완료, 결과 저장)
 * GET study:step4:idem:abc                            → "order-7" (두 번째 요청은 이 결과를 그대로 응답)
 *
 * ── ② 분산 락과 "남의 락을 지우는" 버그 ──
 * [A] SET study:step4:lock token-A NX PX 1000 → OK      (A가 락 획득, 1초 TTL)
 * [B] SET study:step4:lock token-B NX PX 1000 → nil     (B는 대기)
 *     ... A의 작업이 1.5초 걸려 TTL이 먼저 끝남 → 락 자동 해제
 * [B] SET study:step4:lock token-B NX PX 1000 → OK      (B가 락 획득)
 * [A] 작업 끝. 잘못된 해제: DEL study:step4:lock → 1    ← 💥 B의 락을 지워버림!
 *     GET study:step4:lock → nil                        (B는 락이 있다고 믿고 작업 중인데 락이 없음)
 *
 *     올바른 해제: "값이 내 토큰일 때만 DEL" 을 Lua로 한 번에
 * [B] SET study:step4:lock token-B NX PX 1000 → OK
 * [A] EVAL (GET == token-A 이면 DEL) → 0                ← 내 락이 아니니 안 지움
 * [B] EVAL (GET == token-B 이면 DEL) → 1                ← 자기 락만 지움
 * </pre>
 * 쇼핑몰: common/IdempotencyKeys.java, common/RedisLock.java, scripts/lock-release.lua
 */
public class Step4SetNxLock {

    private static final String RELEASE_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0";

    public static void main(String[] args) throws Exception {
        RedisClient client = StudyRedis.newClient();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync();
            StudyRedis.deleteKeys(redis, "study:step4:*");

            System.out.println("── ① 멱등성 키 ──");
            String idem = "study:step4:idem:abc";
            print("SET " + idem + " \"IN_PROGRESS\" NX EX 86400", redis.set(idem, "IN_PROGRESS", SetArgs.Builder.nx().ex(86400)));
            print("SET " + idem + " \"IN_PROGRESS\" NX EX 86400", redis.set(idem, "IN_PROGRESS", SetArgs.Builder.nx().ex(86400)));
            print("SET " + idem + " \"order-7\" EX 86400", redis.set(idem, "order-7", SetArgs.Builder.ex(86400)));
            print("GET " + idem, redis.get(idem));

            System.out.println("── ② 분산 락 ──");
            String lock = "study:step4:lock";
            print("[A] SET " + lock + " token-A NX PX 1000", redis.set(lock, "token-A", SetArgs.Builder.nx().px(1000)));
            print("[B] SET " + lock + " token-B NX PX 1000", redis.set(lock, "token-B", SetArgs.Builder.nx().px(1000)));
            System.out.println("    ... A의 작업이 1.5초 걸려 TTL이 먼저 끝남 → 락 자동 해제");
            Thread.sleep(1_500);
            print("[B] SET " + lock + " token-B NX PX 1000", redis.set(lock, "token-B", SetArgs.Builder.nx().px(1000)));
            print("[A] 작업 끝. 잘못된 해제: DEL " + lock, redis.del(lock) + "    ← 💥 B의 락을 지워버림!");
            print("    GET " + lock, redis.get(lock));

            System.out.println("    올바른 해제: \"값이 내 토큰일 때만 DEL\" 을 Lua로 한 번에");
            print("[B] SET " + lock + " token-B NX PX 1000", redis.set(lock, "token-B", SetArgs.Builder.nx().px(1000)));
            Long byA = redis.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{lock}, "token-A");
            print("[A] EVAL (GET == token-A 이면 DEL)", byA + "    ← 내 락이 아니니 안 지움");
            Long byB = redis.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{lock}, "token-B");
            print("[B] EVAL (GET == token-B 이면 DEL)", byB + "    ← 자기 락만 지움");
        } finally {
            client.shutdown();
        }
    }

    private static void print(String command, Object result) {
        System.out.printf("%-55s → %s%n", command, result == null ? "nil" : result);
    }
}
