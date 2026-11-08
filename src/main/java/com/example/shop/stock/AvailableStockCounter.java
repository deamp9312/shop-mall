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
     * @return product ids whose available stock is short; empty when the reservation succeeded
     */
    List<Long> tryReserve(Map<Long, Integer> quantities) {
        List<Long> productIds = new ArrayList<>(quantities.keySet());
        List<String> keys = productIds.stream().map(RedisKeys::stock).toList();
        Object[] amounts = productIds.stream().map(id -> String.valueOf(quantities.get(id))).toArray();
        List<?> shortLines = redis.execute(RESERVE, keys, amounts);
        return shortLines.stream().map(index -> productIds.get(((Number) index).intValue() - 1)).toList();
    }

    /** Adds every delta (may be negative) in one Lua call; nothing changes if any counter is not an integer. */
    void add(Map<Long, Integer> deltas) {
        runAdd(deltas, ALWAYS);
    }

    /**
     * Reads the resync epoch. Callers read it while their transaction holds the reservation or product rows a resync
     * would wait for, so a later change of the epoch means a resync read the database after this transaction ended.
     */
    String resyncEpoch() {
        String epoch = redis.opsForValue().get(RedisKeys.stockSyncEpoch());
        return epoch == null ? "0" : epoch;
    }

    /**
     * Rollback compensation: adds {@code deltas} unless a resync ran after {@code epochBefore} was read. In that case
     * the resync read the rolled-back database and already wrote the right value; adding again would count it twice.
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
