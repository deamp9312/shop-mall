package com.example.shop.event;

import com.example.shop.common.MdcKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PENDING outbox 행을 id 순서로 Kafka에 발행하고 SENT로 표시한다 (REQ-DA-002 2). CDC 대신 500 ms마다 polling한다.
 * Key = orderId, headers = traceId / eventId / eventType.
 * <p>
 * 전달 보장은 at-least-once이다. broker가 record를 저장했지만 ack가 유실되면 다음 poll에서 그 행을 다시 발행한다.
 * consumer는 eventId로 중복을 걸러낸다 (REQ-NF-002).
 */
@Component
public class OutboxRelay {

    static final int BATCH_SIZE = 100;
    static final Duration SENT_RETENTION = Duration.ofDays(7);
    /** producer의 delivery.timeout.ms보다 길게 잡아, 실패한 발행이 완전히 끝난 뒤에 재시도되게 한다. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);
    private static final long POLL_DELAY_MILLIS = 500;
    private static final long CLEANUP_DELAY_MILLIS = 60 * 60 * 1000;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        // READ COMMITTED는 gap lock을 잡지 않으므로, 잠긴 PENDING 행이 새 outbox 행을 insert하는 주문을 막지 않는다.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelay = POLL_DELAY_MILLIS)
    public void publishPending() {
        transaction.executeWithoutResult(status -> publish(outbox.lockPending(BATCH_SIZE)));
    }

    /** REQ-DA-002 5): SENT 행은 이벤트 화면(REQ-F-014)용으로 7일간 보관한 뒤 삭제한다. */
    @Scheduled(fixedDelay = CLEANUP_DELAY_MILLIS)
    public void deleteOldSentEvents() {
        Integer deleted = transaction.execute(status ->
                outbox.deleteSentBefore(LocalDateTime.now().minus(SENT_RETENTION)));
        if (deleted != null && deleted > 0) {
            log.info("deleted SENT outbox events older than {} days: {}", SENT_RETENTION.toDays(), deleted);
        }
    }

    /**
     * 첫 broker 장애에서 멈추고, 이번 batch에서 이벤트가 실패한 주문의 나머지 이벤트는 건너뛴다. 그래서 같은 주문의
     * 뒤 이벤트가 앞 이벤트보다 먼저 발행되는 일이 없다 (FAILED 행은 lockPending 참고).
     */
    private void publish(List<OutboxEvent> events) {
        Set<String> failedOrders = new HashSet<>();
        for (OutboxEvent event : events) {
            if (failedOrders.contains(event.getMessageKey())) {
                continue;
            }
            PublishResult result = publishOne(event);
            if (result == PublishResult.BROKER_UNAVAILABLE) {
                return;
            }
            if (result == PublishResult.FAILED) {
                failedOrders.add(event.getMessageKey());
            }
        }
    }

    /**
     * ★ 이 프로젝트에서 Kafka로 메시지를 보내는(produce) 유일한 곳이다.
     * <pre>
     * [IN]  outbox_events 행
     *       id=101, event_id="9b1deb4d-...", topic="shop.order.created", message_key="7", status=PENDING,
     *       payload='{"eventId":"9b1deb4d-...","eventType":"ORDER_CREATED","traceId":"f3a9c1d2e4b5",...}'
     * [OUT] Kafka record 전송 (kafka.send)
     *       topic="shop.order.created", key="7", value=payload 문자열 그대로,
     *       headers={traceId:"f3a9c1d2e4b5", eventId:"9b1deb4d-...", eventType:"ORDER_CREATED"}
     *       → broker 응답: partition=1, offset=42 (key "7"의 해시로 partition이 정해진다)
     *       → 성공: 행 status=SENT, sent_at=now          return SENT
     *       → broker 다운/타임아웃: 행 그대로 PENDING     return BROKER_UNAVAILABLE
     *       → 그 외 실패: retry_count+1 (5회면 FAILED)    return FAILED
     * </pre>
     */
    private PublishResult publishOne(OutboxEvent event) {
        EnvelopeHeaders headers = headersOf(event);
        MDC.put(MdcKeys.ORDER_ID, event.getMessageKey());
        MDC.put(MdcKeys.TRACE_ID, headers.traceId());
        try {
            kafka.send(toRecord(event, headers)).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            event.markSent();
            log.info("event published: topic={} eventId={}", event.getTopic(), event.getEventId());
            return PublishResult.SENT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PublishResult.BROKER_UNAVAILABLE;
        } catch (Exception e) {
            return handleFailure(event, e);
        } finally {
            MDC.remove(MdcKeys.ORDER_ID);
            MDC.remove(MdcKeys.TRACE_ID);
        }
    }

    /**
     * broker 장애(timeout, retriable error)는 횟수를 세지 않고 행을 PENDING으로 유지하므로, Kafka가 내려가 있는 동안
     * 기록된 이벤트는 복구 후 발행된다 (REQ-DA-002 verification 1). 그 밖의 실패는 FAILED 쪽으로 횟수에 포함된다.
     */
    private PublishResult handleFailure(OutboxEvent event, Exception failure) {
        if (isBrokerUnavailable(failure)) {
            log.warn("Kafka unavailable, event stays PENDING: eventId={} cause={}", event.getEventId(),
                    failure.toString());
            return PublishResult.BROKER_UNAVAILABLE;
        }
        event.recordFailure();
        log.error("event publish failed: eventId={} attempt={} status={}", event.getEventId(), event.getRetryCount(),
                event.getStatus(), failure);
        return PublishResult.FAILED;
    }

    private static boolean isBrokerUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof RetriableException) {
                return true;
            }
        }
        return false;
    }

    /**
     * outbox 행 → Kafka ProducerRecord 변환.
     * <pre>
     * [IN]  topic="shop.order.created", messageKey="7", payload='{...envelope JSON...}', traceId/eventType(JSON에서 추출)
     * [OUT] ProducerRecord(topic="shop.order.created", key="7", value='{...envelope JSON...}')
     *       + headers traceId / eventId / eventType (byte[] UTF-8)
     * </pre>
     */
    private static ProducerRecord<String, String> toRecord(OutboxEvent event, EnvelopeHeaders headers) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers()
                .add(EventHeaders.TRACE_ID, headers.traceId().getBytes(StandardCharsets.UTF_8))
                .add(EventHeaders.EVENT_ID, event.getEventId().getBytes(StandardCharsets.UTF_8))
                .add(EventHeaders.EVENT_TYPE, headers.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private EnvelopeHeaders headersOf(OutboxEvent event) {
        try {
            JsonNode envelope = objectMapper.readTree(event.getPayload());
            return new EnvelopeHeaders(envelope.path("traceId").asText(""), envelope.path("eventType").asText(""));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox row " + event.getEventId() + " does not hold a JSON envelope", e);
        }
    }

    private enum PublishResult { SENT, FAILED, BROKER_UNAVAILABLE }

    private record EnvelopeHeaders(String traceId, String eventType) {
    }
}
