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
 *
 * Redis 멱등 키 RedisKeys.idempotency(scope, key), TTL 24시간 (REQ-SE-002 2, 3). 처음 호출한 쪽이 SET NX로 키를
 * 선점하고 나중에 결과를 그 키에 저장한다. 끝난 요청을 다시 보내면 저장된 결과를 돌려받고, 실행 중인 요청을 다시 보내면
 * 409로 거절된다. 주문(scope ORDER)과 모의 결제 승인(scope PAYMENT)에서 쓴다.
 * <p>
 * Redis는 첫 번째 방어선일 뿐이다. 키는 만료되거나 유실될 수 있으므로 각 호출자는 두 번째 방어선으로 DB 검사를 둔다.
 */
@Component
public class IdempotencyKeys {

    static final Duration TTL = Duration.ofHours(24);

    /**
     * UUIDs and similar opaque tokens; also keeps arbitrary text out of Redis key names.
     *
     * UUID와 같은 불투명 토큰. 임의의 문자열이 Redis 키 이름에 들어가지 않게 하는 역할도 한다.
     */
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
     *
     * 반환: 이 호출자가 키를 선점했으면 빈 값이며, 이때 {@link #complete} 또는 {@link #abandon}을 반드시 호출해야 한다.
     * 그렇지 않으면 먼저 끝난 요청이 저장한 결과.
     * 예외: 키 형식이 잘못되면 ShopException INVALID_INPUT, 첫 요청이 실행 중이면 REQUEST_IN_PROGRESS.
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
     *
     * 결제 승인의 주문 id처럼 서버가 직접 만든 키를 선점한다 (REQ-SE-002 3). 검증할 클라이언트 입력이 없다. {@code
     * String.valueOf(id)}로 complete 또는 abandon 한다.
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

    /**
     * Releases a claimed key after a failure, so the same request can be sent again.
     *
     * 실패 후 선점한 키를 풀어서 같은 요청을 다시 보낼 수 있게 한다.
     */
    public void abandon(IdempotencyScope scope, String key) {
        redis.delete(RedisKeys.idempotency(scope, key));
    }
}
