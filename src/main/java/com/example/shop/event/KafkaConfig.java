package com.example.shop.event;

import com.example.shop.common.InvalidStateTransitionException;
import com.example.shop.common.ShopException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.ArrayList;
import java.util.List;

/**
 * topic 구성(REQ-IF-002 1, 5)과 모든 listener가 공유하는 consumer 오류 처리(REQ-NF-002 2, 3)이다.
 * Ack mode RECORD, earliest offset reset, producer acks/idempotence 설정은 application.yml에 있다.
 * Spring Boot가 error handler와 {@link TraceRecordInterceptor} bean을 기본 listener factory에 적용한다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /** 1 s, 2 s, 4 s로 세 번 재시도하므로 record는 DLT로 가기 전에 총 네 번 시도된다. */
    private static final int MAX_RETRIES = 3;
    /** 최초 전달에 재시도 횟수를 더한 값이다. */
    static final int DELIVERY_ATTEMPTS = MAX_RETRIES + 1;
    private static final long FIRST_RETRY_INTERVAL_MILLIS = 1_000;
    private static final double RETRY_MULTIPLIER = 2.0;

    @Bean
    KafkaAdmin.NewTopics shopTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : EventTopics.ALL) {
            topics.add(newTopic(topic));
            topics.add(newTopic(topic + EventTopics.DLT_SUFFIX));
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private static NewTopic newTopic(String name) {
        return TopicBuilder.name(name).partitions(EventTopics.PARTITIONS).replicas(EventTopics.REPLICAS).build();
    }

    /**
     * exponential back-off으로 재시도한 뒤 record를 같은 partition의 topic + ".DLT"로 발행한다.
     * 형식이 잘못된 record, 상태 테이블이 금지한 전이, 비즈니스 규칙 위반(ShopException, 예: 존재하지 않는 주문의
     * 이벤트)은 매번 똑같이 실패하므로 재시도를 건너뛴다.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(FIRST_RETRY_INTERVAL_MILLIS);
        backOff.setMultiplier(RETRY_MULTIPLIER);
        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class, InvalidStateTransitionException.class,
                ShopException.class);
        return handler;
    }
}
