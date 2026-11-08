package com.example.shop.order;

/** Why an order status changed: the consumed event, or a reason when no event caused it (REQ-F-012 timeline). */
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
