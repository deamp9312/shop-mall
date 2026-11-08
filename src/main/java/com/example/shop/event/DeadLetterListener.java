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
 * Stores every *.DLT record in dead_letter_events (REQ-NF-002 4) with the original topic and the exception message
 * that DeadLetterPublishingRecoverer put in the headers.
 */
@Component
class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final DeadLetterEventRepository deadLetters;

    DeadLetterListener(DeadLetterEventRepository deadLetters) {
        this.deadLetters = deadLetters;
    }

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
