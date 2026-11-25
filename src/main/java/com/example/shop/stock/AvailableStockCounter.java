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
 * Redis 가용 재고 카운터 RedisKeys.stock(productId)를 쓴다 (REQ-F-007). 선점은 모든 라인을 한 번의 Lua 호출로
 * 처리하므로 두 주문이 마지막 재고 1개를 함께 가져갈 수 없다 (DB 락 없음, synchronized 없음).
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
     * 모든 라인을 차감한다. 한 라인이라도 부족하면 아무것도 차감하지 않는다.
     * <pre>
     * [IN]  quantities={3: 2, 5: 1}  (productId → 주문 수량)
     * [Redis] EVAL stock-reserve.lua KEYS=[shop:stock:3, shop:stock:5] ARGV=["2","1"]
     *           = GET shop:stock:3 → "50", GET shop:stock:5 → "0"
     *             5번이 부족 → 아무것도 차감하지 않고 return [2]   (부족한 줄의 순번, 1부터)
     *           (모두 충분했다면 DECRBY shop:stock:3 2, DECRBY shop:stock:5 1 후 return [])
     * [OUT] [5] (재고 부족 상품 id) / 성공이면 []
     * </pre>
     *
     * @return 가용 재고가 부족한 상품 id 목록. 선점에 성공하면 비어 있다
     */
    List<Long> tryReserve(Map<Long, Integer> quantities) {
        List<Long> productIds = new ArrayList<>(quantities.keySet());
        List<String> keys = productIds.stream().map(RedisKeys::stock).toList();
        Object[] amounts = productIds.stream().map(id -> String.valueOf(quantities.get(id))).toArray();
        List<?> shortLines = redis.execute(RESERVE, keys, amounts);
        return shortLines.stream().map(index -> productIds.get(((Number) index).intValue() - 1)).toList();
    }

    /**
     * 모든 delta(음수 가능)를 한 번의 Lua 호출로 더한다. 정수가 아닌 카운터가 하나라도 있으면 아무것도 안 바꾼다.
     * <pre>
     * [IN]  deltas={3: 2}  (예: 결제 실패로 2개 반환. 관리자 재고 감소면 음수)
     * [Redis] EVAL stock-add.lua KEYS=[shop:stock-sync:epoch, shop:stock:3] ARGV=["", "2"]
     *           = (ARGV[1]이 ""이라 epoch 검사 생략) INCRBY shop:stock:3 2  → 48 → 50
     * </pre>
     */
    void add(Map<Long, Integer> deltas) {
        runAdd(deltas, ALWAYS);
    }

    /**
     * resync epoch를 읽는다. 호출자는 재동기화가 기다려야 하는 선점 행이나 상품 행을 트랜잭션이 잡고 있는 동안 이 값을
     * 읽는다. 따라서 이후 epoch가 바뀌었다면 재동기화가 이 트랜잭션이 끝난 뒤 DB를 읽었다는 뜻이다.
     * <pre>
     * [Redis] GET shop:stock-sync:epoch → "12"  (없으면 "0")
     * [OUT] "12"
     * </pre>
     */
    String resyncEpoch() {
        String epoch = redis.opsForValue().get(RedisKeys.stockSyncEpoch());
        return epoch == null ? "0" : epoch;
    }

    /**
     * 롤백 보상이다. {@code epochBefore}를 읽은 뒤 재동기화가 실행되지 않았을 때만 {@code deltas}를 더한다. 실행됐다면
     * 재동기화가 롤백된 DB를 읽어 이미 올바른 값을 썼으므로, 다시 더하면 두 번 반영된다.
     * <pre>
     * [IN]  deltas={3: 2} (차감했던 2개를 되돌림), epochBefore="12"
     * [Redis] EVAL stock-add.lua KEYS=[shop:stock-sync:epoch, shop:stock:3] ARGV=["12", "2"]
     *           = GET shop:stock-sync:epoch → "12" 같음 → INCRBY shop:stock:3 2 → return 1 (적용)
     *                                       → "13" 다름 → 아무것도 안 함    → return 0 (건너뜀)
     * [OUT] true(적용) / false(재동기화가 먼저 돌아서 건너뜀)
     * </pre>
     *
     * @return 재동기화 때문에 건너뛰었으면 false
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
