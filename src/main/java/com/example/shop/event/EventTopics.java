package com.example.shop.event;

import java.util.List;

/**
 * Kafka topics of REQ-IF-002 (naming rule shop.&lt;domain&gt;.&lt;past-tense verb&gt;). Each topic has a dead-letter topic
 * named topic + {@link #DLT_SUFFIX}. Constants, so that {@code @KafkaListener} can use them.
 *
 * REQ-IF-002의 Kafka 토픽 (이름 규칙 shop.&lt;도메인&gt;.&lt;과거형 동사&gt;). 토픽마다 토픽 + {@link #DLT_SUFFIX}
 * 이름의 DLT 토픽이 있다. {@code @KafkaListener}에서 쓸 수 있도록 상수로 둔다.
 */
public final class EventTopics {

    public static final String ORDER_CREATED = "shop.order.created";
    public static final String ORDER_CANCELLED = "shop.order.cancelled";
    public static final String PAYMENT_COMPLETED = "shop.payment.completed";
    public static final String PAYMENT_FAILED = "shop.payment.failed";
    public static final String PAYMENT_CANCELLED = "shop.payment.cancelled";
    public static final String PAYMENT_CANCEL_REQUESTED = "shop.payment.cancel-requested";

    /**
     * Suffix used by DeadLetterPublishingRecoverer's default destination (same partition).
     *
     * DeadLetterPublishingRecoverer의 기본 목적지(같은 파티션)에 쓰이는 접미사.
     */
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
    /**
     * Local single broker (REQ-IF-002 1).
     *
     * 로컬 단일 브로커 (REQ-IF-002 1).
     */
    public static final short REPLICAS = 1;

    private EventTopics() {
    }
}
