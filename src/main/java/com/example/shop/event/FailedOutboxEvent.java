package com.example.shop.event;

import java.time.LocalDateTime;

/**
 * 이벤트 화면(REQ-F-014)에 표시되는, 발행을 포기한 outbox 행이다 (REQ-DA-002). heldBack은 이 행을 기다리고 있는
 * 같은 주문의 후속 PENDING 행 수이다.
 */
public record FailedOutboxEvent(long id, String eventId, String topic, String messageKey, int retryCount,
                                LocalDateTime createdAt, long heldBack) {
}
