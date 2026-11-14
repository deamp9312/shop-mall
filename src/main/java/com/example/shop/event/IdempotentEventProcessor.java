package com.example.shop.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * Idempotent consumption shared by every listener (REQ-NF-002 1): the business handler and the
 * processed_events(consumer_group, event_id) row commit in one transaction. A redelivered event finds its row and is
 * skipped; a handler failure rolls the row back, so the retry handles the event again.
 *
 * 모든 리스너가 함께 쓰는 멱등 소비 (REQ-NF-002 1). 업무 핸들러와 processed_events(consumer_group, event_id) 행이 한
 * 트랜잭션으로 커밋된다. 다시 전달된 이벤트는 자기 행을 찾아 건너뛰고, 핸들러가 실패하면 행도 롤백되므로 재시도에서
 * 이벤트를 다시 처리한다.
 */
@Component
public class IdempotentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventProcessor.class);

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ConsumerFailureSwitch failureSwitch;

    public IdempotentEventProcessor(ObjectMapper objectMapper, JdbcTemplate jdbc, TransactionTemplate transaction,
                                    ConsumerFailureSwitch failureSwitch) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.failureSwitch = failureSwitch;
    }

    public <P> void process(String consumerGroup, ConsumerRecord<String, String> record, Class<P> payloadType,
                            Consumer<EventEnvelope<P>> handler) {
        EventEnvelope<P> event = parse(record, payloadType);
        transaction.executeWithoutResult(status -> {
            if (!markProcessed(consumerGroup, event.eventId())) {
                log.info("duplicate event skipped: group={} type={} eventId={}", consumerGroup, event.eventType(),
                        event.eventId());
                return;
            }
            failureSwitch.failIfArmed(consumerGroup, event.eventId());
            handler.accept(event);
            log.info("event consumed: group={} type={} eventId={}", consumerGroup, event.eventType(), event.eventId());
        });
    }

    /**
     * Inserting first makes a concurrent duplicate wait on the primary key and then fail, instead of both passing a
     * "not yet processed" check. MySQL keeps the transaction usable after a duplicate-key error.
     *
     * 먼저 insert하면 동시에 들어온 중복 요청은 기본 키에서 기다렸다가 실패한다. 둘 다 "아직 처리 안 됨" 검사를
     * 통과하는 일이 없다. MySQL은 중복 키 오류 뒤에도 트랜잭션을 계속 쓸 수 있다.
     */
    private boolean markProcessed(String consumerGroup, String eventId) {
        try {
            jdbc.update("insert into processed_events (consumer_group, event_id, processed_at) values (?, ?, ?)",
                    consumerGroup, eventId, Timestamp.valueOf(LocalDateTime.now()));
            return true;
        } catch (DuplicateKeyException alreadyProcessed) {
            return false;
        }
    }

    private <P> EventEnvelope<P> parse(ConsumerRecord<String, String> record, Class<P> payloadType) {
        JavaType envelopeType = objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        EventEnvelope<P> event;
        try {
            event = objectMapper.readValue(record.value(), envelopeType);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new MalformedEventException(record.topic(), e);
        }
        if (event == null || !event.hasRequiredFields()) {
            throw new MalformedEventException(record.topic());
        }
        return event;
    }
}
