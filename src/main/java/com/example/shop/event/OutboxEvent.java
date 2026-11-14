package com.example.shop.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One event written in the same transaction as the domain change (transactional outbox, REQ-DA-002) and sent to Kafka
 * later by {@link OutboxRelay}. payload holds the whole JSON envelope.
 *
 * 도메인 변경과 같은 트랜잭션에서 기록되고(트랜잭셔널 아웃박스, REQ-DA-002), 나중에 {@link OutboxRelay}가 Kafka로
 * 보내는 이벤트 하나. payload에는 JSON envelope 전체가 들어 있다.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    protected OutboxEvent() {
    }

    OutboxEvent(String eventId, String topic, String messageKey, String payload) {
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    void markSent() {
        status = OutboxStatus.SENT;
        sentAt = LocalDateTime.now();
    }

    /**
     * Sends the row again on the next poll (admin retry of a dead letter, REQ-F-014 2).
     *
     * 다음 폴링에서 행을 다시 보낸다 (DLT 이벤트의 관리자 재처리, REQ-F-014 2).
     */
    void requeue() {
        status = OutboxStatus.PENDING;
        sentAt = null;
        retryCount = 0;
    }

    /**
     * Counts a failed send; the fifth one gives up (REQ-DA-002: FAILED after 5 failures).
     *
     * 전송 실패를 센다. 다섯 번째 실패에서 포기한다 (REQ-DA-002: 5회 실패 후 FAILED).
     */
    void recordFailure() {
        retryCount++;
        if (retryCount >= MAX_ATTEMPTS) {
            status = OutboxStatus.FAILED;
        }
    }

    String getEventId() {
        return eventId;
    }

    String getTopic() {
        return topic;
    }

    String getMessageKey() {
        return messageKey;
    }

    String getPayload() {
        return payload;
    }

    OutboxStatus getStatus() {
        return status;
    }

    int getRetryCount() {
        return retryCount;
    }
}
