package com.example.shop.common;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Redis idempotency keys RedisKeys.idempotency(scope, key), TTL 24 hours (REQ-SE-002 2, 3). The first caller claims
 * the key with SET NX and later stores its result in it; a repeat of a finished request gets that result back, a repeat
 * of a running one is rejected with 409. Used by orders (scope ORDER) and the mock payment approval (scope PAYMENT).
 * <p>
 * Redis is the first layer only; each caller keeps a DB guard as the second one, because a key can expire or be lost.
 */
@Component
public class IdempotencyKeys {

    static final Duration TTL = Duration.ofHours(24);

    /** UUIDs and similar opaque tokens; also keeps arbitrary text out of Redis key names. */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final StringRedisTemplate redis;

    public IdempotencyKeys(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return empty when this caller claimed the key and must {@link #complete} or {@link #abandon} it; otherwise the
     *         result stored by the request that finished first
     * @throws ShopException INVALID_INPUT for a malformed key, REQUEST_IN_PROGRESS while the first request runs
     */
    public Optional<String> claim(IdempotencyScope scope, String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new ShopException(ErrorCode.INVALID_INPUT);
        }
        return claimRedisKey(RedisKeys.idempotency(scope, key));
    }

    /**
     * Claims a key the server derives itself, such as the order id of a payment approval (REQ-SE-002 3), so there is
     * no client text to validate. Complete or abandon it with {@code String.valueOf(id)}.
     */
    public Optional<String> claim(IdempotencyScope scope, long id) {
        return claimRedisKey(RedisKeys.idempotency(scope, String.valueOf(id)));
    }

    private Optional<String> claimRedisKey(String redisKey) {
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, TTL))) {
            return Optional.empty();
        }
        String stored = redis.opsForValue().get(redisKey);
        if (stored == null || IN_PROGRESS.equals(stored)) {
            throw new ShopException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        return Optional.of(stored);
    }

    public void complete(IdempotencyScope scope, String key, String result) {
        redis.opsForValue().set(RedisKeys.idempotency(scope, key), result, TTL);
    }

    /** Releases a claimed key after a failure, so the same request can be sent again. */
    public void abandon(IdempotencyScope scope, String key) {
        redis.delete(RedisKeys.idempotency(scope, key));
    }
}
