package com.example.shop.common;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Redis 멱등성 키 RedisKeys.idempotency(scope, key), TTL 24시간 (REQ-SE-002 2, 3). 첫 호출자가 SET NX로 키를
 * 선점하고 나중에 결과를 저장한다. 완료된 요청을 반복하면 저장된 결과를 돌려받고, 실행 중인 요청을 반복하면 409로
 * 거절된다. 주문(scope ORDER)과 모의 결제 승인(scope PAYMENT)에서 사용한다.
 * <p>
 * Redis는 1차 방어선일 뿐이다. 키는 만료되거나 유실될 수 있으므로 각 호출자는 2차 방어선으로 DB 가드를 둔다.
 */
@Component
public class IdempotencyKeys {

    static final Duration TTL = Duration.ofHours(24);

    /** UUID 같은 불투명 토큰만 허용한다. 임의 텍스트가 Redis 키 이름에 들어가는 것도 막는다. */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final StringRedisTemplate redis;

    public IdempotencyKeys(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return 이 호출자가 키를 선점했으면 empty이며, 이때 반드시 {@link #complete} 또는 {@link #abandon}을 호출해야
     *         한다. 그렇지 않으면 먼저 끝난 요청이 저장한 결과
     * @throws ShopException 형식이 잘못된 키면 INVALID_INPUT, 첫 요청이 실행 중이면 REQUEST_IN_PROGRESS
     */
    public Optional<String> claim(IdempotencyScope scope, String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new ShopException(ErrorCode.INVALID_INPUT);
        }
        return claimRedisKey(RedisKeys.idempotency(scope, key));
    }

    /**
     * 결제 승인의 주문 id처럼 서버가 직접 만든 키를 선점한다 (REQ-SE-002 3). 따라서 검증할 클라이언트 입력이 없다.
     * 완료나 포기는 {@code String.valueOf(id)}로 한다.
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

    /** 실패 후 선점한 키를 해제해 같은 요청을 다시 보낼 수 있게 한다. */
    public void abandon(IdempotencyScope scope, String key) {
        redis.delete(RedisKeys.idempotency(scope, key));
    }
}
