package com.example.shop.event;

import java.util.List;

/**
 * Kafka consumer groups (REQ-IF-002 6). Each module consumes with its own group, so each gets every event.
 *
 * Kafka 컨슈머 그룹 (REQ-IF-002 6). 모듈마다 자기 그룹으로 소비하므로 각 모듈이 모든 이벤트를 받는다.
 */
public final class ConsumerGroups {

    public static final String ORDER = "shop-order";
    public static final String PAYMENT = "shop-payment";
    public static final String STOCK = "shop-stock";
    public static final String NOTIFICATION = "shop-notification";
    /**
     * The four module groups; the failure switch of the admin event screen accepts only these (REQ-F-014 3).
     *
     * 네 개의 모듈 그룹. 관리자 이벤트 화면의 장애 스위치는 이 그룹만 받는다 (REQ-F-014 3).
     */
    public static final List<String> MODULE_GROUPS = List.of(ORDER, PAYMENT, STOCK, NOTIFICATION);
    /**
     * Stores *.DLT records in dead_letter_events (REQ-NF-002 4). Not one of the four module groups.
     *
     * *.DLT 레코드를 dead_letter_events에 저장한다 (REQ-NF-002 4). 네 모듈 그룹에 속하지 않는다.
     */
    public static final String DEAD_LETTER = "shop-dead-letter";

    private ConsumerGroups() {
    }
}
