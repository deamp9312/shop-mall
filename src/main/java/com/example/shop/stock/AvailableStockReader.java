package com.example.shop.stock;

import com.example.shop.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 가용 재고 카운터를 읽는다 (REQ-F-003 4, REQ-F-004 2). 캐시하지 않는다. 키가 없으면 0으로 읽으므로
 * 초과 판매하는 대신 품절로 표시된다.
 */
@Component
public class AvailableStockReader {

    private final StringRedisTemplate redis;

    public AvailableStockReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * <pre>
     * [IN]  productId=3
     * [Redis] GET shop:stock:3 → "48"   (키가 없으면 nil → 0, 품절로 표시)
     * [OUT] 48
     * </pre>
     */
    public long availableOf(long productId) {
        return parse(redis.opsForValue().get(RedisKeys.stock(productId)));
    }

    /**
     * <pre>
     * [IN]  productIds=[3, 5, 9]
     * [Redis] MGET shop:stock:3 shop:stock:5 shop:stock:9 → ["48", "0", nil]   (한 번의 왕복으로 여러 개 조회)
     * [OUT] {3: 48, 5: 0, 9: 0}
     * </pre>
     */
    public Map<Long, Long> availableOf(List<Long> productIds) {
        Map<Long, Long> available = new HashMap<>();
        if (productIds.isEmpty()) {
            return available;
        }
        List<String> values = redis.opsForValue().multiGet(productIds.stream().map(RedisKeys::stock).toList());
        for (int i = 0; i < productIds.size(); i++) {
            available.put(productIds.get(i), parse(values == null ? null : values.get(i)));
        }
        return available;
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
