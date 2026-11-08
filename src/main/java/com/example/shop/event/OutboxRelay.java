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
 */
@Component
public class OutboxRelay {

    static final int BATCH_SIZE = 100;
    static final Duration SENT_RETENTION = Duration.ofDays(7);
    /** Longer than the producer's delivery.timeout.ms, so a failed send is really over before it is retried. */
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
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelay = POLL_DELAY_MILLIS)
    public void publishPending() {
        transaction.executeWithoutResult(status -> publish(outbox.lockPending(BATCH_SIZE)));
    }

    /** REQ-DA-002 5): SENT rows are kept 7 days for the event screen (REQ-F-014), then deleted. */
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
