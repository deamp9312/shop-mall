package com.example.shop.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests: full application on a random port, test profile, shared containers.
 *
 * 통합 테스트의 기본 클래스: 무작위 포트의 전체 애플리케이션, test 프로필, 공유 컨테이너.
 */
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
