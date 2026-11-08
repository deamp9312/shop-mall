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
 * Product cache: key RedisKeys.product(id), TTL 10 minutes, plain JSON of {@link ProductInfo} without Java class names,
 * so a changed class never fails to deserialize old JDK bytes (REQ-F-004).
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
