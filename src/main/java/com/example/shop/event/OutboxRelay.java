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
 * Sends PENDING outbox rows to Kafka in id order and marks them SENT (REQ-DA-002 2). Polls every 500 ms instead of
 * CDC. Key = orderId, headers = traceId / eventId / eventType.
 * <p>
 * Delivery is at-least-once: if the broker stored a record but the ack was lost, the row is sent again on the next
 * poll. Consumers drop the duplicate by eventId (REQ-NF-002).
 *
 * PENDING 아웃박스 행을 id 순서로 Kafka에 보내고 SENT로 표시한다 (REQ-DA-002 2). CDC 대신 500ms마다 폴링한다. 키 =
 * orderId, 헤더 = traceId / eventId / eventType.
 * <p>
 * 전달은 최소 1회(at-least-once)다. 브로커가 레코드를 저장했지만 ack가 유실되면 다음 폴링에서 그 행을 다시 보낸다.
 * 컨슈머는 eventId로 중복을 버린다 (REQ-NF-002).
 */
@Component
public class OutboxRelay {

    static final int BATCH_SIZE = 100;
    static final Duration SENT_RETENTION = Duration.ofDays(7);
    /**
     * Longer than the producer's delivery.timeout.ms, so a failed send is really over before it is retried.
     *
     * 프로듀서의 delivery.timeout.ms보다 길게 잡아서, 실패한 전송이 완전히 끝난 뒤에 재시도한다.
     */
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
        // READ COMMITTED takes no gap locks, so the locked PENDING rows never block orders inserting new outbox rows.
        // READ COMMITTED는 갭 락을 잡지 않으므로, 잠근 PENDING 행이 새 아웃박스 행을 넣는 주문을 막지 않는다.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelay = POLL_DELAY_MILLIS)
    public void publishPending() {
        transaction.executeWithoutResult(status -> publish(outbox.lockPending(BATCH_SIZE)));
    }

    /**
     * REQ-DA-002 5): SENT rows are kept 7 days for the event screen (REQ-F-014), then deleted.
     *
     * REQ-DA-002 5): SENT 행은 이벤트 화면용으로 7일 동안 보관한 뒤 삭제한다 (REQ-F-014).
     */
    @Scheduled(fixedDelay = CLEANUP_DELAY_MILLIS)
    public void deleteOldSentEvents() {
        Integer deleted = transaction.execute(status ->
                outbox.deleteSentBefore(LocalDateTime.now().minus(SENT_RETENTION)));
        if (deleted != null && deleted > 0) {
            log.info("deleted SENT outbox events older than {} days: {}", SENT_RETENTION.toDays(), deleted);
        }
    }

    /**
     * Stops at the first broker outage, and skips the rest of an order whose event failed in this batch, so a later
     * event of the same order is never sent before an earlier one (FAILED rows: see lockPending).
     *
     * 첫 브로커 장애에서 멈추고, 이번 배치에서 이벤트가 실패한 주문의 나머지 행은 건너뛴다. 그래서 같은 주문의 뒤
     * 이벤트가 앞 이벤트보다 먼저 전송되는 일이 없다 (FAILED 행은 lockPending 참고).
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
     * A broker outage (timeout, retriable error) keeps the row PENDING without counting, so events written while
     * Kafka is down are sent after it comes back (REQ-DA-002 verification 1). Any other failure counts toward FAILED.
     *
     * 브로커 장애(타임아웃, 재시도 가능한 오류)는 실패로 세지 않고 행을 PENDING으로 둔다. 그래서 Kafka가 내려가 있는
     * 동안 기록된 이벤트는 복구 후에 전송된다 (REQ-DA-002 검증 1). 그 밖의 실패는 FAILED 쪽으로 센다.
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
