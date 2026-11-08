package com.example.shop.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base class for integration tests: full application on a random port, test profile, shared containers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestInfrastructure.register(registry, TestInfrastructure.DEFAULT_DATABASE,
                TestInfrastructure.DEFAULT_REDIS_DATABASE,
                TestInfrastructure.KAFKA);
    }
}
