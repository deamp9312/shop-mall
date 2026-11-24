package com.example.shop.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 모든 *.DLT record를 원본 topic, 그리고 DeadLetterPublishingRecoverer가 header에 넣은 예외 메시지와 함께
 * dead_letter_events에 저장한다 (REQ-NF-002 4).
 */
@Component
class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final DeadLetterEventRepository deadLetters;

    DeadLetterListener(DeadLetterEventRepository deadLetters) {
        this.deadLetters = deadLetters;
    }

    /**
     * <pre>
     * [IN]  topic="shop.order.created.DLT", key="7",
     *       value='{"eventId":"9b1deb4d-...","eventType":"ORDER_CREATED",...}' (원본 그대로)
     *       headers: kafka_dlt-original-topic="shop.order.created",
     *                kafka_dlt-exception-message="Listener method ... threw exception; forced failure ..."
     * [OUT] dead_letter_events INSERT
     *       original_topic="shop.order.created", payload='{...원본 JSON...}', error_message="...", retried_at=null
     *       → 관리자 이벤트 화면에 표시됨
     * </pre>
     */
    @KafkaListener(groupId = ConsumerGroups.DEAD_LETTER, topics = {
            EventTopics.ORDER_CREATED_DLT, EventTopics.ORDER_CANCELLED_DLT, EventTopics.PAYMENT_COMPLETED_DLT,
            EventTopics.PAYMENT_FAILED_DLT, EventTopics.PAYMENT_CANCELLED_DLT, EventTopics.PAYMENT_CANCEL_REQUESTED_DLT})
    void store(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (originalTopic == null) {
            originalTopic = record.topic().substring(0, record.topic().length() - EventTopics.DLT_SUFFIX.length());
        }
        String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        deadLetters.save(new DeadLetterEvent(originalTopic, String.valueOf(record.value()), error));
        log.warn("dead letter stored: originalTopic={} error={}", originalTopic, error);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
