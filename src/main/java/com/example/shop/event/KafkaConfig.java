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
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /** 1 s, 2 s, 4 s: three retries, so a record is tried four times before it goes to the DLT. */
    private static final int MAX_RETRIES = 3;
    /** First delivery plus the retries. */
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
