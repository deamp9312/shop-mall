package com.example.shop.common;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * RedisKeys.lock(name) 기반 분산 락이다 (SET NX PX + 소유자 확인 후 해제). TTL은 작업 시간보다 길어야 한다.
 * 그렇지 않으면 작업이 끝나기 전에 다른 인스턴스가 진입할 수 있다.
 */
@Component
public class RedisLock {

    private static final RedisScript<Long> RELEASE =
            RedisScript.of(new ClassPathResource("scripts/lock-release.lua"), Long.class);

    private final StringRedisTemplate redis;

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 락이 비어 있을 때만 작업을 실행하고 그 결과를 반환한다. 그렇지 않으면 {@code whenBusy}를 던진다. */
    public <T> T runExclusively(String name, Duration ttl, ErrorCode whenBusy, Supplier<T> task) {
        String key = RedisKeys.lock(name);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ShopException(whenBusy);
        }
        try {
            return task.get();
        } finally {
            redis.execute(RELEASE, List.of(key), token);
        }
    }
}
