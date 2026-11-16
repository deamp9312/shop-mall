package com.example.shop.order;

/** 주문 상태가 바뀐 원인: 소비한 이벤트, 또는 이벤트가 원인이 아닌 경우의 사유 (REQ-F-012 타임라인). */
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
