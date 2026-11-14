package com.example.shop.event;

import java.time.LocalDateTime;

/**
 * An outbox row that gave up (REQ-DA-002) as shown on the event screen (REQ-F-014). heldBack is the number of later
 * PENDING rows of the same order that wait for it.
 *
 * 이벤트 화면에 보이는, 포기한 아웃박스 행 (REQ-DA-002, REQ-F-014). heldBack은 이 행을 기다리는 같은 주문의 뒤쪽
 * PENDING 행 수다.
 */
public record FailedOutboxEvent(long id, String eventId, String topic, String messageKey, int retryCount,
                                LocalDateTime createdAt, long heldBack) {
}
