package com.example.shop.order;

/**
 * Why an order status changed: the consumed event, or a reason when no event caused it (REQ-F-012 timeline).
 *
 * 주문 상태가 바뀐 이유: 소비한 이벤트, 또는 이벤트가 원인이 아닐 때는 사유 (REQ-F-012 타임라인).
 */
public record StatusChangeCause(String reason, String eventId, String eventType) {

    private static final String ORDER_PLACED = "ORDER_PLACED";

    public static StatusChangeCause ofEvent(String eventId, String eventType) {
        return new StatusChangeCause(null, eventId, eventType);
    }

    static StatusChangeCause orderPlaced() {
        return new StatusChangeCause(ORDER_PLACED, null, null);
    }

    static StatusChangeCause cancelled(CancelReason reason) {
        return new StatusChangeCause(reason.name(), null, null);
    }
}
