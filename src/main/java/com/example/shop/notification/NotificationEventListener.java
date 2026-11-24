package com.example.shop.notification;

import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.EventTopics;
import com.example.shop.event.IdempotentEventProcessor;
import com.example.shop.event.PaymentCancelledEvent;
import com.example.shop.event.PaymentCompletedEvent;
import com.example.shop.event.PaymentFailedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 알림 모듈 컨슈머(group shop-notification). 결제 결과 이벤트 하나당 알림 하나를 남긴다. */
@Component
class NotificationEventListener {

    private final IdempotentEventProcessor processor;
    private final NotificationService notifications;

    NotificationEventListener(IdempotentEventProcessor processor, NotificationService notifications) {
        this.processor = processor;
        this.notifications = notifications;
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.completed, key="7"
     *       value={"eventId":"7a8b...","eventType":"PAYMENT_COMPLETED",...,
     *              "payload":{"orderId":7,"paymentId":11,"amount":17800,"approvedAt":"2026-11-24T10:03:21"}}
     * [OUT] notification_logs INSERT (order_id=7, type=PAYMENT_COMPLETED, event_id="7a8b...")
     *       ※ 같은 이벤트를 shop-order, shop-stock group도 각자 받는다 (group이 다르면 모두 받음)
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.NOTIFICATION)
    void onPaymentCompleted(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.NOTIFICATION, record, PaymentCompletedEvent.class, event ->
                notifications.record(event.payload().orderId(), NotificationType.PAYMENT_COMPLETED, event.eventId()));
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.failed, key="7"
     *       value={"eventId":"5c6d...","eventType":"PAYMENT_FAILED",...,"payload":{"orderId":7,"paymentId":11}}
     * [OUT] notification_logs INSERT (order_id=7, type=PAYMENT_FAILED, event_id="5c6d...")
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = ConsumerGroups.NOTIFICATION)
    void onPaymentFailed(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.NOTIFICATION, record, PaymentFailedEvent.class, event ->
                notifications.record(event.payload().orderId(), NotificationType.PAYMENT_FAILED, event.eventId()));
    }

    /**
     * <pre>
     * [IN]  topic=shop.payment.cancelled, key="7"
     *       value={"eventId":"e1f2...","eventType":"PAYMENT_CANCELLED",...,"payload":{"orderId":7,"paymentId":11}}
     * [OUT] notification_logs INSERT (order_id=7, type=PAYMENT_CANCELLED, event_id="e1f2...")
     * </pre>
     */
    @KafkaListener(topics = EventTopics.PAYMENT_CANCELLED, groupId = ConsumerGroups.NOTIFICATION)
    void onPaymentCancelled(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.NOTIFICATION, record, PaymentCancelledEvent.class, event ->
                notifications.record(event.payload().orderId(), NotificationType.PAYMENT_CANCELLED, event.eventId()));
    }
}
