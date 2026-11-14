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
 * Topics (REQ-IF-002 1, 5) and the consumer error handling shared by every listener (REQ-NF-002 2, 3).
 * Ack mode RECORD, earliest offset reset and producer acks/idempotence are in application.yml.
 * Spring Boot applies the error handler and {@link TraceRecordInterceptor} beans to the default listener factory.
 *
 * 토픽 (REQ-IF-002 1, 5)과 모든 리스너가 함께 쓰는 컨슈머 오류 처리 (REQ-NF-002 2, 3).
 * ack 모드 RECORD, earliest 오프셋 리셋, 프로듀서 acks/멱등성 설정은 application.yml에 있다.
 * Spring Boot가 오류 핸들러와 {@link TraceRecordInterceptor} 빈을 기본 리스너 팩토리에 적용한다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /**
     * 1 s, 2 s, 4 s: three retries, so a record is tried four times before it goes to the DLT.
     *
     * 1초, 2초, 4초: 재시도 3회이므로 레코드는 DLT로 가기 전에 4번 시도된다.
     */
    private static final int MAX_RETRIES = 3;
    /**
     * First delivery plus the retries.
     *
     * 첫 전달과 재시도를 합친 횟수.
     */
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
     * Retries with exponential back-off, then publishes the record to topic + ".DLT" on the same partition.
     * A malformed record, a transition the state table forbids or a business rule failure (ShopException, for
     * example an event for an order that does not exist) would fail the same way every time, so it skips the retries.
     *
     * 지수 백오프로 재시도한 뒤 레코드를 같은 파티션의 토픽 + ".DLT"로 발행한다.
     * 형식이 깨진 레코드, 상태표가 금지하는 전이, 업무 규칙 실패(ShopException, 예: 존재하지 않는 주문의 이벤트)는 매번
     * 같은 방식으로 실패하므로 재시도를 건너뛴다.
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
