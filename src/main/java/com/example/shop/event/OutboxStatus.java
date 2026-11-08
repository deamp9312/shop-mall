package com.example.shop.event;

/** outbox_events.status (REQ-DA-002 1). */
public enum OutboxStatus {
    PENDING,
    SENT,
    /** Gave up after {@link OutboxEvent#MAX_ATTEMPTS} failures that were not a broker outage; shown to the admin. */
    FAILED
}
