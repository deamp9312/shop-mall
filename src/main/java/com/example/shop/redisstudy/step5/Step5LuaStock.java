package com.example.shop.redisstudy.step5;

import com.example.shop.redisstudy.StudyRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STEP 5. Lua 스크립트 — "확인하고 → 차감"을 한 번에 (재고 초과 판매 막기).
 * <p>
 * 핵심: STEP 2의 INCR처럼 명령 하나면 원자적이지만, "재고가 충분한지 확인 → 차감"은 명령이 두 개다.
 * 그 사이에 다른 요청이 끼어들면 둘 다 "재고 있음"을 보고 차감해서 <b>초과 판매</b>가 난다.
 * Lua 스크립트는 Redis 안에서 통째로 실행되고 그동안 다른 명령이 끼어들지 못한다 → 여러 명령을 원자적으로 묶는 방법.
 * <pre>
 * [IN]  재고 study:step5:stock = 10, 구매 요청 50건이 동시에 (각 1개)
 *
 * ① GET 으로 확인 → DECRBY (잘못된 방법)
 *    요청 A: GET → "1" (있네!)     요청 B: GET → "1" (있네!)
 *    요청 A: DECRBY 1 → 0          요청 B: DECRBY 1 → -1    ← 초과 판매
 * [OUT] 판매 성공 = 27건, 남은 재고 = -17   (실행마다 다름)
 *
 * ② Lua 스크립트 (올바른 방법)
 *    EVAL "local s = tonumber(GET) ; if s < 1 then return -1 end ; return DECRBY(1)" 1 study:step5:stock 1
 * [OUT] 판매 성공 = 10건, 남은 재고 = 0
 * </pre>
 * 쇼핑몰: scripts/stock-reserve.lua — 주문의 모든 상품을 한 번에 확인하고, 하나라도 부족하면 아무것도 차감하지 않는다.
 * (DB 락도 synchronized 도 없이 동시 주문을 막는다. stock/AvailableStockCounter.tryReserve)
 */
public class Step5LuaStock {

    private static final String STOCK = "study:step5:stock";
    private static final int REQUESTS = 50;

    /** KEYS[1] 재고 키, ARGV[1] 구매 수량. 성공하면 남은 재고, 부족하면 -1. */
    private static final String RESERVE_SCRIPT = """
            local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
            if stock < tonumber(ARGV[1]) then
                return -1
            end
            return redis.call('DECRBY', KEYS[1], ARGV[1])
            """;

    public static void main(String[] args) throws Exception {
        RedisClient client = StudyRedis.newClient();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync();
            StudyRedis.deleteKeys(redis, "study:step5:*");

            redis.set(STOCK, "10");
            int sold = runConcurrently(() -> {
                long stock = Long.parseLong(redis.get(STOCK)); // 1) 확인
                if (stock < 1) {
                    return false;
                }
                sleepBriefly();                                // (결제 검증 등 처리 시간이 있다고 가정)
                redis.decrby(STOCK, 1);                        // 2) 차감 — 그 사이 다른 요청도 "재고 있음"을 봤다
                return true;
            });
            System.out.printf("① GET 확인 → DECRBY : 판매 성공 = %d건, 남은 재고 = %s%n", sold, redis.get(STOCK));

            redis.set(STOCK, "10");
            sold = runConcurrently(() -> {
                Long left = redis.eval(RESERVE_SCRIPT, ScriptOutputType.INTEGER, new String[]{STOCK}, "1");
                return left >= 0;
            });
            System.out.printf("② Lua 스크립트       : 판매 성공 = %d건, 남은 재고 = %s%n", sold, redis.get(STOCK));
        } finally {
            client.shutdown();
        }
    }

    private interface Purchase {
        boolean tryBuy();
    }

    private static int runConcurrently(Purchase purchase) throws InterruptedException {
        AtomicInteger sold = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        for (int i = 0; i < REQUESTS; i++) {
            pool.submit(() -> {
                if (purchase.tryBuy()) {
                    sold.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        return sold.get();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
