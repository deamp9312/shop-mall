package com.example.shop.stock;

import com.example.shop.common.RedisKeys;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes the Redis available-stock counters RedisKeys.stock(productId) (REQ-F-007). Reservation is one Lua call for
 * all lines, so two orders can never both take the last unit (no DB lock, no synchronized).
 *
 * Redis 가용 재고 카운터 RedisKeys.stock(productId)를 쓴다 (REQ-F-007). 선점은 모든 줄을 Lua 호출 한 번으로 처리하므로,
 * 두 주문이 마지막 한 개를 동시에 가져가는 일이 없다 (DB 락도 synchronized도 쓰지 않는다).
 */
@Component
class AvailableStockCounter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE =
            RedisScript.of(new ClassPathResource("scripts/stock-reserve.lua"), List.class);

    private static final RedisScript<Long> ADD =
            RedisScript.of(new ClassPathResource("scripts/stock-add.lua"), Long.class);
    private static final String ALWAYS = "";

    private final StringRedisTemplate redis;

    AvailableStockCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Decrements every line, or none of them if any line is short.
     *
     * 모든 줄을 차감한다. 한 줄이라도 부족하면 아무것도 차감하지 않는다.
     * 반환: 가용 재고가 부족한 상품 id 목록. 선점에 성공하면 빈 목록.
     *
     * @return product ids whose available stock is short; empty when the reservation succeeded
     */
    List<Long> tryReserve(Map<Long, Integer> quantities) {
        List<Long> productIds = new ArrayList<>(quantities.keySet());
        List<String> keys = productIds.stream().map(RedisKeys::stock).toList();
        Object[] amounts = productIds.stream().map(id -> String.valueOf(quantities.get(id))).toArray();
        List<?> shortLines = redis.execute(RESERVE, keys, amounts);
        return shortLines.stream().map(index -> productIds.get(((Number) index).intValue() - 1)).toList();
    }

    /**
     * Adds every delta (may be negative) in one Lua call; nothing changes if any counter is not an integer.
     *
     * 모든 증감값(음수 가능)을 Lua 호출 한 번으로 더한다. 정수가 아닌 카운터가 하나라도 있으면 아무것도 바꾸지 않는다.
     */
    void add(Map<Long, Integer> deltas) {
        runAdd(deltas, ALWAYS);
    }

    /**
     * Reads the resync epoch. Callers read it while their transaction holds the reservation or product rows a resync
     * would wait for, so a later change of the epoch means a resync read the database after this transaction ended.
     *
     * 재동기화 epoch을 읽는다. 호출자는 재동기화가 기다릴 선점 행이나 상품 행을 트랜잭션이 잡고 있을 때 이 값을 읽는다.
     * 따라서 나중에 epoch이 바뀌었다면 이 트랜잭션이 끝난 뒤 재동기화가 DB를 읽었다는 뜻이다.
     */
    String resyncEpoch() {
        String epoch = redis.opsForValue().get(RedisKeys.stockSyncEpoch());
        return epoch == null ? "0" : epoch;
    }

    /**
     * Rollback compensation: adds {@code deltas} unless a resync ran after {@code epochBefore} was read. In that case
     * the resync read the rolled-back database and already wrote the right value; adding again would count it twice.
     *
     * 롤백 보상: {@code epochBefore}를 읽은 뒤 재동기화가 실행되지 않았으면 {@code deltas}를 더한다. 재동기화가
     * 실행되었다면 롤백된 DB를 읽어 이미 올바른 값을 썼으므로, 다시 더하면 두 번 세게 된다.
     * 반환: 재동기화 때문에 건너뛰었으면 false
     *
     * @return false when skipped because of a resync
     */
    boolean addUnlessResynced(Map<Long, Integer> deltas, String epochBefore) {
        return runAdd(deltas, epochBefore);
    }

    static Map<Long, Integer> negated(Map<Long, Integer> deltas) {
        Map<Long, Integer> negated = new TreeMap<>();
        deltas.forEach((productId, delta) -> negated.put(productId, -delta));
        return negated;
    }

    private boolean runAdd(Map<Long, Integer> deltas, String expectedEpoch) {
        List<Long> productIds = new ArrayList<>(deltas.keySet());
        List<String> keys = Stream.concat(Stream.of(RedisKeys.stockSyncEpoch()),
                productIds.stream().map(RedisKeys::stock)).toList();
        Object[] args = Stream.concat(Stream.of(expectedEpoch),
                productIds.stream().map(id -> String.valueOf(deltas.get(id)))).toArray();
        Long applied = redis.execute(ADD, keys, args);
        return applied != null && applied == 1L;
    }
}
