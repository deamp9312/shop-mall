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

/** Payment module consumer (group shop-payment). */
@Component
class PaymentEventListener {

    private final IdempotentEventProcessor processor;
    private final PaymentLifecycleService payments;

    PaymentEventListener(IdempotentEventProcessor processor, PaymentLifecycleService payments) {
        this.processor = processor;
        this.payments = payments;
    }

    @KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = ConsumerGroups.PAYMENT)
    void onOrderCreated(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.PAYMENT, record, OrderCreatedEvent.class,
                event -> payments.prepare(event.payload(), event.traceId()));
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = ConsumerGroups.PAYMENT)
    void onOrderCancelled(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.PAYMENT, record, OrderCancelledEvent.class,
                event -> payments.cancelForOrder(event.payload().orderId()));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_CANCEL_REQUESTED, groupId = ConsumerGroups.PAYMENT)
    void onCancelRequested(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.PAYMENT, record, PaymentCancelRequestedEvent.class,
                event -> payments.cancelForOrder(event.payload().orderId()));
    }
}
