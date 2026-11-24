package com.example.shop.stock;

import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.EventTopics;
import com.example.shop.event.IdempotentEventProcessor;
import com.example.shop.event.OrderCancelledEvent;
import com.example.shop.event.PaymentCompletedEvent;
import com.example.shop.event.PaymentFailedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 재고 모듈 컨슈머 (group shop-stock): 결제 성공 시 확정하고, 결제 실패나 취소 시 해제한다. */
@Component
class StockEventListener {

    private final IdempotentEventProcessor processor;
    private final StockReservationService reservations;

    StockEventListener(IdempotentEventProcessor processor, StockReservationService reservations) {
        this.processor = processor;
        this.reservations = reservations;
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.completed, key="7"
     *       value={...,"eventType":"PAYMENT_COMPLETED",
     *              "payload":{"orderId":7,"paymentId":11,"amount":17800,"approvedAt":"2026-11-24T10:03:21"}}
     * [OUT] stock_reservations(order_id=7) RESERVED → CONFIRMED, DB 재고 차감 (예: product 3 재고 50 → 48)
     *       Redis 재고는 주문 시 이미 차감했으므로 그대로. Kafka 발행 없음
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.STOCK)
    void onPaymentCompleted(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.STOCK, record, PaymentCompletedEvent.class,
                event -> reservations.confirm(event.payload().orderId()));
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.failed, key="7"
     *       value={...,"eventType":"PAYMENT_FAILED","payload":{"orderId":7,"paymentId":11}}
     * [OUT] stock_reservations(order_id=7) RESERVED → RELEASED, Redis 재고 복구 (예: product 3 가용재고 48 → 50). Kafka 발행 없음
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = ConsumerGroups.STOCK)
    void onPaymentFailed(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.STOCK, record, PaymentFailedEvent.class,
                event -> reservations.releaseReserved(event.payload().orderId()));
    }

    /**
     * <pre>
     * [IN]  topic=shop.order.cancelled, key="7"
     *       value={...,"eventType":"ORDER_CANCELLED","payload":{"orderId":7,"memberId":1,"reason":"USER_CANCEL"}}
     * [OUT] RESERVED 행  → RELEASED, Redis 재고 복구
     *       CONFIRMED 행 → RELEASED, DB 재고 + Redis 재고 모두 복구 (결제까지 끝난 주문을 취소한 경우)
     *       Kafka 발행 없음
     * </pre>
     */
    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = ConsumerGroups.STOCK)
    void onOrderCancelled(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.STOCK, record, OrderCancelledEvent.class,
                event -> reservations.releaseAll(event.payload().orderId()));
    }
}
