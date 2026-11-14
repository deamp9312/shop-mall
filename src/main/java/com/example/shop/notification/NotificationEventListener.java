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

/**
 * Notification module consumer (group shop-notification): one notice per payment result event.
 *
 * 알림 모듈 컨슈머 (그룹 shop-notification): 결제 결과 이벤트마다 알림 하나를 남긴다.
 */
@Component
class NotificationEventListener {

    private final IdempotentEventProcessor processor;
    private final NotificationService notifications;

    NotificationEventListener(IdempotentEventProcessor processor, NotificationService notifications) {
        this.processor = processor;
        this.notifications = notifications;
    }

    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.NOTIFICATION)
    void onPaymentCompleted(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.NOTIFICATION, record, PaymentCompletedEvent.class, event ->
                notifications.record(event.payload().orderId(), NotificationType.PAYMENT_COMPLETED, event.eventId()));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = ConsumerGroups.NOTIFICATION)
    void onPaymentFailed(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.NOTIFICATION, record, PaymentFailedEvent.class, event ->
                notifications.record(event.payload().orderId(), NotificationType.PAYMENT_FAILED, event.eventId()));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_CANCELLED, groupId = ConsumerGroups.NOTIFICATION)
    void onPaymentCancelled(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.NOTIFICATION, record, PaymentCancelledEvent.class, event ->
                notifications.record(event.payload().orderId(), NotificationType.PAYMENT_CANCELLED, event.eventId()));
    }
}
