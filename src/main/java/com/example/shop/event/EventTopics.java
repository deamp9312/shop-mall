package com.example.shop.event;

import java.util.List;

/**
 * REQ-IF-002의 Kafka topic이다 (명명 규칙 shop.&lt;domain&gt;.&lt;past-tense verb&gt;). 각 topic에는 topic +
 * {@link #DLT_SUFFIX} 이름의 dead-letter topic이 있다. {@code @KafkaListener}에서 쓸 수 있도록 상수로 둔다.
 */
public final class EventTopics {

    public static final String ORDER_CREATED = "shop.order.created";
    public static final String ORDER_CANCELLED = "shop.order.cancelled";
    public static final String PAYMENT_COMPLETED = "shop.payment.completed";
    public static final String PAYMENT_FAILED = "shop.payment.failed";
    public static final String PAYMENT_CANCELLED = "shop.payment.cancelled";
    public static final String PAYMENT_CANCEL_REQUESTED = "shop.payment.cancel-requested";

    /** DeadLetterPublishingRecoverer의 기본 목적지(같은 partition)가 쓰는 접미어이다. */
    public static final String DLT_SUFFIX = ".DLT";

    public static final String ORDER_CREATED_DLT = ORDER_CREATED + DLT_SUFFIX;
    public static final String ORDER_CANCELLED_DLT = ORDER_CANCELLED + DLT_SUFFIX;
    public static final String PAYMENT_COMPLETED_DLT = PAYMENT_COMPLETED + DLT_SUFFIX;
    public static final String PAYMENT_FAILED_DLT = PAYMENT_FAILED + DLT_SUFFIX;
    public static final String PAYMENT_CANCELLED_DLT = PAYMENT_CANCELLED + DLT_SUFFIX;
    public static final String PAYMENT_CANCEL_REQUESTED_DLT = PAYMENT_CANCEL_REQUESTED + DLT_SUFFIX;

    public static final List<String> ALL = List.of(ORDER_CREATED, ORDER_CANCELLED, PAYMENT_COMPLETED, PAYMENT_FAILED,
            PAYMENT_CANCELLED, PAYMENT_CANCEL_REQUESTED);

    public static final int PARTITIONS = 3;
    /** 로컬 단일 broker 기준이다 (REQ-IF-002 1). */
    public static final short REPLICAS = 1;

    private EventTopics() {
    }
}
