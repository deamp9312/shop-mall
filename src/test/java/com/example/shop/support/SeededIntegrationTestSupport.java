package com.example.shop.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for tests that need the demo seed (as the local profile applies it): own MySQL database shop_seed and
 * Redis database 1, so seed rows never leak into tests on the empty schema. Tests that change seed rows or Redis
 * values must restore them, because every subclass shares this one context.
 *
 * 데모 시드가 필요한 테스트(local 프로필이 적용하는 것처럼)의 기본 클래스: 전용 MySQL DB shop_seed와 Redis DB 1을 써서
 * 시드 행이 빈 스키마 테스트로 새지 않게 한다. 모든 하위 클래스가 이 컨텍스트 하나를 공유하므로, 시드 행이나 Redis 값을
 * 바꾸는 테스트는 원래대로 돌려놓아야 한다.
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class SeededIntegrationTestSupport {

    private static final String SEED_DATABASE = "shop_seed";
    private static final int SEED_REDIS_DATABASE = 1;

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestInfrastructure.register(registry, SEED_DATABASE, SEED_REDIS_DATABASE, TestInfrastructure.SEED_KAFKA);
    }
}
