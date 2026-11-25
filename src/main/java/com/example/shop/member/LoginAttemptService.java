package com.example.shop.member;

import com.example.shop.common.RedisKeys;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** 로그인 5회 실패 시 해당 이메일을 5분간 잠근다 (REQ-SE-001 4). 카운터 키: RedisKeys.loginFailure. */
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

    /**
     * <pre>
     * [IN]  email="User@Shop.com"
     * [Redis] GET shop:login:fail:user@shop.com
     *           → (nil) 또는 "3" : return false (로그인 시도 허용)
     *           → "5"            : return true  (잠김, 키 TTL 5분이 끝나면 자동 해제)
     * </pre>
     */
    public boolean isLocked(String email) {
        String failures = redis.opsForValue().get(RedisKeys.loginFailure(email));
        return failures != null && Integer.parseInt(failures) >= MAX_FAILURES;
    }

    /**
     * <pre>
     * [IN]  email="user@shop.com" (비밀번호 틀림)
     * [Redis] EVAL login-failure.lua KEYS=[shop:login:fail:user@shop.com] ARGV=["300000"]
     *           = INCR shop:login:fail:user@shop.com → 3 (실패 횟수)
     *             PEXPIRE shop:login:fail:user@shop.com 300000   (5분 잠금 구간 재시작)
     * </pre>
     */
    public void recordFailure(String email) {
        redis.execute(RECORD_FAILURE, List.of(RedisKeys.loginFailure(email)),
                String.valueOf(LOCK_DURATION.toMillis()));
    }

    /**
     * <pre>
     * [IN]  email="user@shop.com" (로그인 성공)
     * [Redis] DEL shop:login:fail:user@shop.com
     * </pre>
     */
    public void reset(String email) {
        redis.delete(RedisKeys.loginFailure(email));
    }
}
