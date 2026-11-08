package com.example.shop.member;

import com.example.shop.common.RedisKeys;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** Locks an email for 5 minutes after 5 failed logins (REQ-SE-001 4). Counter key: RedisKeys.loginFailure. */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private static final RedisScript<Long> RECORD_FAILURE =
            RedisScript.of(new ClassPathResource("scripts/login-failure.lua"), Long.class);

    private final StringRedisTemplate redis;

    public LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLocked(String email) {
        String failures = redis.opsForValue().get(RedisKeys.loginFailure(email));
        return failures != null && Integer.parseInt(failures) >= MAX_FAILURES;
    }

    public void recordFailure(String email) {
        redis.execute(RECORD_FAILURE, List.of(RedisKeys.loginFailure(email)),
                String.valueOf(LOCK_DURATION.toMillis()));
    }

    public void reset(String email) {
        redis.delete(RedisKeys.loginFailure(email));
    }
}
