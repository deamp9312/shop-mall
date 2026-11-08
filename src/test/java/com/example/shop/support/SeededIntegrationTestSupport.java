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
