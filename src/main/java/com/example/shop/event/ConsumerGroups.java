package com.example.shop.event;

import java.util.List;

/** Kafka consumer group 목록이다 (REQ-IF-002 6). 모듈마다 자체 group으로 소비하므로 각 모듈이 모든 이벤트를 받는다. */
public final class ConsumerGroups {

    public static final String ORDER = "shop-order";
    public static final String PAYMENT = "shop-payment";
    public static final String STOCK = "shop-stock";
    public static final String NOTIFICATION = "shop-notification";
    /** 네 개의 모듈 group이다. 관리자 이벤트 화면의 장애 스위치는 이 group만 받는다 (REQ-F-014 3). */
    public static final List<String> MODULE_GROUPS = List.of(ORDER, PAYMENT, STOCK, NOTIFICATION);
    /** *.DLT record를 dead_letter_events에 저장한다 (REQ-NF-002 4). 네 모듈 group에는 속하지 않는다. */
    public static final String DEAD_LETTER = "shop-dead-letter";

    private ConsumerGroups() {
    }
}
