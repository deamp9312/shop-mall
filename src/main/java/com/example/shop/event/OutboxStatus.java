package com.example.shop.event;

/**
 * outbox_events.status (REQ-DA-002 1).
 *
 * outbox_events.status (REQ-DA-002 1).
 */
public enum OutboxStatus {
    PENDING,
    SENT,
    /**
     * Gave up after {@link OutboxEvent#MAX_ATTEMPTS} failures that were not a broker outage; shown to the admin.
     *
     * 브로커 장애가 아닌 실패가 {@link OutboxEvent#MAX_ATTEMPTS}번 나서 포기한 상태. 관리자에게 보인다.
     */
    FAILED
}
