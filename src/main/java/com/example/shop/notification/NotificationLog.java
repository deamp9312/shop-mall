package com.example.shop.notification;

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
 * Payment result notice stored instead of sending mail or SMS (REQ-F-009 4), shown on the order detail page.
 *
 * 메일이나 SMS를 보내는 대신 저장하는 결제 결과 알림 (REQ-F-009 4). 주문 상세 화면에 보인다.
 */
@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false)
    private String message;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected NotificationLog() {
    }

    NotificationLog(long orderId, long memberId, NotificationType type, String eventId) {
        this.orderId = orderId;
        this.memberId = memberId;
        this.type = type;
        this.message = type.message();
        this.eventId = eventId;
        this.createdAt = LocalDateTime.now();
    }

    public NotificationType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
