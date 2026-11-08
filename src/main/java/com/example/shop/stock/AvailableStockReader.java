package com.example.shop.stock;

import com.example.shop.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Redis available-stock counters (REQ-F-003 4, REQ-F-004 2). Never cached. A missing key reads as 0, which
 * shows the product as sold out instead of overselling it.
 */
@Component
public class AvailableStockReader {

    private final StringRedisTemplate redis;

    public AvailableStockReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long availableOf(long productId) {
        return parse(redis.opsForValue().get(RedisKeys.stock(productId)));
    }

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
