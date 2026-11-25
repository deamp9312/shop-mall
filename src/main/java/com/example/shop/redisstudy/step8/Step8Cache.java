package com.example.shop.redisstudy.step8;

import com.example.shop.redisstudy.StudyRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * STEP 8. 캐시 — {@code @Cacheable} / {@code @CacheEvict} 와 Redis.
 * <p>
 * 핵심: 느린 조회(DB) 결과를 Redis에 잠깐 저장해 두고, 다음 요청은 Redis에서 바로 돌려준다 (cache-aside).
 * <ul>
 *   <li>{@code @Cacheable}: 캐시에 있으면 메서드를 실행하지 않고 캐시 값을 반환, 없으면 실행 후 저장</li>
 *   <li>{@code @CacheEvict}: 데이터가 바뀌면 캐시를 지운다. 안 지우면 TTL이 끝날 때까지 옛날 값이 보인다</li>
 *   <li>TTL: 지우는 걸 깜빡해도 언젠가는 최신 값으로 돌아오게 하는 안전장치</li>
 * </ul>
 * <pre>
 * [IN/OUT]
 * 1) getProduct(3)  → DB 조회 (1초)  → Product[id=3, name=반팔 티셔츠, price=8900]   1003ms  (cache miss)
 *    Redis 저장됨: GET study:step8:product:3 → {"id":3,"name":"반팔 티셔츠","price":8900}   TTL 10초
 * 2) getProduct(3)  → Redis에서 바로      → Product[id=3, name=반팔 티셔츠, price=8900]   4ms     (cache hit)
 * 3) changePrice(3, 9900) → DB 수정 + DEL study:step8:product:3   (@CacheEvict)
 * 4) getProduct(3)  → DB 조회 (1초)  → Product[id=3, name=반팔 티셔츠, price=9900]   1002ms  (새 값)
 * </pre>
 * 쇼핑몰: product/ProductCatalog.getProduct (@Cacheable), product/ProductAdminService (@CacheEvict),
 * product/ProductCacheConfig (TTL 10분, JSON 직렬화)
 * <p>
 * 해볼 것: changePrice 의 {@code @CacheEvict}를 지우고 실행 → 4)에서 옛날 가격 8900이 나온다 (캐시 불일치).
 */
public class Step8Cache {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            ProductService products = context.getBean(ProductService.class);
            StringRedisTemplate redis = new StringRedisTemplate(context.getBean(LettuceConnectionFactory.class));
            redis.delete("study:step8:product:3");

            timed("1) getProduct(3)", () -> products.getProduct(3));
            System.out.println("   Redis 저장됨: GET study:step8:product:3 → " + redis.opsForValue().get("study:step8:product:3")
                    + "   TTL " + redis.getExpire("study:step8:product:3") + "초");
            timed("2) getProduct(3)", () -> products.getProduct(3));
            products.changePrice(3, 9900);
            System.out.println("3) changePrice(3, 9900) → DB 수정 + 캐시 삭제. 지금 Redis 값: "
                    + redis.opsForValue().get("study:step8:product:3"));
            timed("4) getProduct(3)", () -> products.getProduct(3));
        }
    }

    private static void timed(String label, java.util.function.Supplier<Product> call) {
        long start = System.nanoTime();
        Product product = call.get();
        System.out.printf("%s → %s   %dms%n", label, product, (System.nanoTime() - start) / 1_000_000);
    }

    public record Product(long id, String name, long price) {
    }

    /** {@code @Configuration}을 붙이지 않았다: 쇼핑몰 앱의 컴포넌트 스캔에 잡히지 않게 하려는 것. */
    @EnableCaching
    static class Config {

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory redisConnectionFactory() {
            return StudyRedis.newConnectionFactory();
        }

        /** 쇼핑몰 ProductCacheConfig 와 같은 방식: 키 접두어, TTL, 클래스명 없는 순수 JSON. */
        @Bean
        CacheManager cacheManager(LettuceConnectionFactory connectionFactory) {
            RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofSeconds(10))                              // 학습용으로 짧게 (쇼핑몰은 10분)
                    .computePrefixWith(cacheName -> "study:step8:" + cacheName + ":") // → study:step8:product:3
                    .serializeValuesWith(SerializationPair.fromSerializer(
                            new Jackson2JsonRedisSerializer<>(new ObjectMapper(), Product.class)));
            return RedisCacheManager.builder(connectionFactory).cacheDefaults(config).build();
        }

        @Bean
        ProductService productService() {
            return new ProductService();
        }
    }

    /** DB 대신 Map + 1초 지연으로 "느린 조회"를 흉내 낸다. */
    static class ProductService {

        private final java.util.Map<Long, Product> database = new java.util.concurrent.ConcurrentHashMap<>(
                java.util.Map.of(3L, new Product(3, "반팔 티셔츠", 8900)));

        /**
         * <pre>
         * [IN]  productId=3
         * [Redis] GET study:step8:product:3 → 있으면 이 메서드 본문은 실행되지 않음
         *                                   → 없으면 본문 실행 후 SET study:step8:product:3 '{...}' PX 10000
         * [OUT] Product[id=3, name=반팔 티셔츠, price=8900]
         * </pre>
         */
        @Cacheable(cacheNames = "product", key = "#productId")
        public Product getProduct(long productId) {
            try {
                Thread.sleep(1_000); // 느린 DB 조회
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return database.get(productId);
        }

        /**
         * <pre>
         * [IN]  productId=3, price=9900
         * [Redis] (메서드 성공 후) DEL study:step8:product:3
         * </pre>
         */
        @CacheEvict(cacheNames = "product", key = "#productId")
        public void changePrice(long productId, long price) {
            Product old = database.get(productId);
            database.put(productId, new Product(productId, old.name(), price));
        }
    }
}
