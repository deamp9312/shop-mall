package com.example.shop.event;

/** outbox_events.status (REQ-DA-002 1). */
public enum OutboxStatus {
    PENDING,
    SENT,
    /** 브로커 장애가 아닌 실패가 {@link OutboxEvent#MAX_ATTEMPTS}회 누적되어 포기한 상태이다. 관리자에게 노출된다. */
    FAILED
}
