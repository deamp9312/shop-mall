package com.example.shop.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisSessionRepository;

/** Stores HTTP sessions under RedisKeys.SESSION_NAMESPACE (REQ-IF-003). TTL comes from server.servlet.session.timeout. */
@Configuration(proxyBeanMethods = false)
class RedisSessionConfig {

    @Bean
    SessionRepositoryCustomizer<RedisSessionRepository> sessionKeyNamespace() {
        return repository -> repository.setRedisKeyNamespace(RedisKeys.SESSION_NAMESPACE);
    }
}
