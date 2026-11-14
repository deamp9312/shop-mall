package com.example.shop.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A record that reached a *.DLT topic (REQ-NF-002 4). Listed and re-sent from the admin event screen (REQ-F-014).
 *
 * *.DLT 토픽에 도달한 레코드 (REQ-NF-002 4). 관리자 이벤트 화면에서 조회하고 다시 보낸다 (REQ-F-014).
 */
@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

    static final int ERROR_MESSAGE_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_topic", nullable = false, length = 100)
    private String originalTopic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "error_message", length = ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "retried_at")
    private LocalDateTime retriedAt;

    protected DeadLetterEvent() {
    }

    DeadLetterEvent(String originalTopic, String payload, String errorMessage) {
        this.originalTopic = originalTopic;
        this.payload = payload;
        this.errorMessage = errorMessage == null || errorMessage.length() <= ERROR_MESSAGE_LENGTH
                ? errorMessage
                : errorMessage.substring(0, ERROR_MESSAGE_LENGTH);
        this.createdAt = LocalDateTime.now();
    }

    boolean isRetried() {
        return retriedAt != null;
    }

    void markRetried() {
        retriedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public String getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getRetriedAt() {
        return retriedAt;
    }
}
