package com.example.shop.product;

import com.example.shop.common.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * 상품 캐시: 키 RedisKeys.product(id), TTL 10분, Java 클래스명 없는 {@link ProductInfo}의 순수 JSON으로 저장한다.
 * 따라서 클래스가 바뀌어도 기존 JDK 직렬화 바이트를 역직렬화하다 실패하는 일이 없다(REQ-F-004).
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class ProductCacheConfig {

    static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    @Bean
    RedisCacheManagerBuilderCustomizer productCache(ObjectMapper objectMapper) {
        RedisCacheConfiguration productConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(PRODUCT_TTL)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> RedisKeys.PRODUCT_CACHE_PREFIX)
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, ProductInfo.class)));
        return builder -> builder.withCacheConfiguration(ProductCatalog.CACHE_NAME, productConfig);
    }
}
