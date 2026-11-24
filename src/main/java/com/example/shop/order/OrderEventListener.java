package com.example.shop.order;

import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.EventTopics;
import com.example.shop.event.IdempotentEventProcessor;
import com.example.shop.event.PaymentCompletedEvent;
import com.example.shop.event.PaymentFailedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문 모듈 컨슈머(group shop-order). 각 변경의 이력 행에는 소비한 이벤트를 원인으로 기록한다
 * (REQ-F-012 3).
 */
@Component
class OrderEventListener {

    private final IdempotentEventProcessor processor;
    private final OrderPaymentResultService results;

    OrderEventListener(IdempotentEventProcessor processor, OrderPaymentResultService results) {
        this.processor = processor;
        this.results = results;
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.completed, key="7"
     *       value={"eventId":"7a8b...","eventType":"PAYMENT_COMPLETED","aggregateId":7,...,
     *              "payload":{"orderId":7,"paymentId":11,"amount":17800,"approvedAt":"2026-11-24T10:03:21"}}
     * [OUT] orders(id=7).status PAYMENT_PENDING → PAID + order_status_history 1행 (event_id="7a8b...")
     *       단, 주문이 이미 취소됐으면: shop.payment.cancel-requested 발행
     *         {"orderId":7,"reason":"ORDER_ALREADY_CANCELLED"}
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.ORDER)
    void onPaymentCompleted(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.ORDER, record, PaymentCompletedEvent.class,
                event -> results.applyPaymentCompleted(event.payload().orderId(), event.payload().approvedAt(),
                        StatusChangeCause.ofEvent(event.eventId(), event.eventType())));
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.failed, key="7"
     *       value={"eventId":"5c6d...","eventType":"PAYMENT_FAILED","aggregateId":7,...,
     *              "payload":{"orderId":7,"paymentId":11}}
     * [OUT] orders(id=7).status PAYMENT_PENDING → PAYMENT_FAILED + order_status_history 1행
     *       Kafka 발행 없음
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = ConsumerGroups.ORDER)
    void onPaymentFailed(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.ORDER, record, PaymentFailedEvent.class,
                event -> results.applyPaymentFailed(event.payload().orderId(),
                        StatusChangeCause.ofEvent(event.eventId(), event.eventType())));
    }
}
