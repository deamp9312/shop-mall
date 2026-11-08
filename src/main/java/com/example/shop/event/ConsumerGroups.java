package com.example.shop.event;

import java.util.List;

/** Kafka consumer groups (REQ-IF-002 6). Each module consumes with its own group, so each gets every event. */
public final class ConsumerGroups {

    public static final String ORDER = "shop-order";
    public static final String PAYMENT = "shop-payment";
    public static final String STOCK = "shop-stock";
    public static final String NOTIFICATION = "shop-notification";
    /** The four module groups; the failure switch of the admin event screen accepts only these (REQ-F-014 3). */
    public static final List<String> MODULE_GROUPS = List.of(ORDER, PAYMENT, STOCK, NOTIFICATION);
    /** Stores *.DLT records in dead_letter_events (REQ-NF-002 4). Not one of the four module groups. */
    public static final String DEAD_LETTER = "shop-dead-letter";

    private ConsumerGroups() {
    }
}
