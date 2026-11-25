package com.example.shop.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisSessionRepository;

/**
 * HTTP 세션을 RedisKeys.SESSION_NAMESPACE 아래에 저장한다 (REQ-IF-003). TTL은 server.servlet.session.timeout을 따른다.
 * <pre>
 * 직접 Redis를 호출하는 코드는 없고 Spring Session이 요청마다 알아서 읽고 쓴다.
 * [IN]  브라우저 쿠키 SESSION=ZGY0YjU2... (세션 id를 base64로 인코딩한 값)
 * [Redis] HGETALL shop:session:sessions:df4b56...        → 로그인 정보(SecurityContext) 등 세션 속성
 *         HSET    shop:session:sessions:df4b56... lastAccessedTime 1764032400000 ...  (요청마다 만료 시간 연장)
 * [OUT] 서버가 여러 대여도 같은 로그인 상태를 공유한다
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
class RedisSessionConfig {

    @Bean
    SessionRepositoryCustomizer<RedisSessionRepository> sessionKeyNamespace() {
        return repository -> repository.setRedisKeyNamespace(RedisKeys.SESSION_NAMESPACE);
    }
}
