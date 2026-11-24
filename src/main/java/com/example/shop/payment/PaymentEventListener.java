package com.example.shop.payment;

import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.EventTopics;
import com.example.shop.event.IdempotentEventProcessor;
import com.example.shop.event.OrderCancelledEvent;
import com.example.shop.event.OrderCreatedEvent;
import com.example.shop.event.PaymentCancelRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 결제 모듈 컨슈머 (group shop-payment). */
@Component
class PaymentEventListener {

    private final IdempotentEventProcessor processor;
    private final PaymentLifecycleService payments;

    PaymentEventListener(IdempotentEventProcessor processor, PaymentLifecycleService payments) {
        this.processor = processor;
        this.payments = payments;
    }

    /**
     * <pre>
     * [IN]  topic=shop.order.created, key="7"
     *       value={"eventId":"9b1deb4d-...","eventType":"ORDER_CREATED","aggregateId":7,"traceId":"f3a9c1d2e4b5",
     *              "payload":{"orderId":7,"memberId":1,"items":[{"productId":3,"qty":2,"price":8900}],
     *                         "totalAmount":17800,"expiresAt":"2026-11-24T10:15:00"}}
     * [OUT] payments INSERT (order_id=7, amount=17800, status=READY, expires_at=2026-11-24T10:15)
     *       Kafka로 나가는 메시지는 없음. (사용자가 결제하면 MockPgApprovalService가 payment.completed/failed 발행)
     * </pre>
     */
    @KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = ConsumerGroups.PAYMENT)
    void onOrderCreated(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.PAYMENT, record, OrderCreatedEvent.class,
                event -> payments.prepare(event.payload(), event.traceId()));
    }

    /**
     * <pre>
     * [IN]  topic=shop.order.cancelled, key="7"
     *       value={"eventId":"c2f1...","eventType":"ORDER_CANCELLED","aggregateId":7,...,
     *              "payload":{"orderId":7,"memberId":1,"reason":"USER_CANCEL"}}   (reason: USER_CANCEL | PAYMENT_TIMEOUT)
     * [OUT] 결제가 READY면:    payments.status READY → CANCELLED (Kafka 발행 없음)
     *       결제가 APPROVED면: 환불 후 CANCELLED + shop.payment.cancelled 발행 {"orderId":7,"paymentId":11}
     * </pre>
     */
    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = ConsumerGroups.PAYMENT)
    void onOrderCancelled(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.PAYMENT, record, OrderCancelledEvent.class,
                event -> payments.cancelForOrder(event.payload().orderId()));
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.cancel-requested, key="7"
     *       value={"eventId":"d4e5...","eventType":"PAYMENT_CANCEL_REQUESTED","aggregateId":7,...,
     *              "payload":{"orderId":7,"reason":"ORDER_ALREADY_CANCELLED"}}
     * [OUT] 결제 APPROVED → 환불 후 CANCELLED + shop.payment.cancelled 발행 {"orderId":7,"paymentId":11}
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_CANCEL_REQUESTED, groupId = ConsumerGroups.PAYMENT)
    void onCancelRequested(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.PAYMENT, record, PaymentCancelRequestedEvent.class,
                event -> payments.cancelForOrder(event.payload().orderId()));
    }
}
