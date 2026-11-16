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
 * 모든 listener가 공유하는 멱등 소비 처리이다 (REQ-NF-002 1). 비즈니스 handler와
 * processed_events(consumer_group, event_id) 행은 한 트랜잭션으로 commit된다. 재전달된 이벤트는 기존 행을 발견해
 * 건너뛰고, handler가 실패하면 행도 롤백되므로 재시도 시 이벤트를 다시 처리한다.
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
     * 먼저 insert하므로 동시에 들어온 중복은 둘 다 "아직 처리 안 됨" 확인을 통과하는 대신 primary key에서 대기한 뒤
     * 실패한다. MySQL은 duplicate-key 오류 후에도 트랜잭션을 계속 사용할 수 있게 유지한다.
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
