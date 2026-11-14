package com.example.shop.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisSessionRepository;

/**
 * Stores HTTP sessions under RedisKeys.SESSION_NAMESPACE (REQ-IF-003). TTL comes from server.servlet.session.timeout.
 *
 * HTTP 세션을 RedisKeys.SESSION_NAMESPACE 아래에 저장한다 (REQ-IF-003). TTL은 server.servlet.session.timeout을 따른다.
 */
@Configuration(proxyBeanMethods = false)
class RedisSessionConfig {

    @Bean
    SessionRepositoryCustomizer<RedisSessionRepository> sessionKeyNamespace() {
        return repository -> repository.setRedisKeyNamespace(RedisKeys.SESSION_NAMESPACE);
    }
}
