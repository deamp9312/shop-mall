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
 * Order module consumer (group shop-order). The history row of each change records the consumed event as its cause
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

    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.ORDER)
    void onPaymentCompleted(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.ORDER, record, PaymentCompletedEvent.class,
                event -> results.applyPaymentCompleted(event.payload().orderId(), event.payload().approvedAt(),
                        StatusChangeCause.ofEvent(event.eventId(), event.eventType())));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = ConsumerGroups.ORDER)
    void onPaymentFailed(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.ORDER, record, PaymentFailedEvent.class,
                event -> results.applyPaymentFailed(event.payload().orderId(),
                        StatusChangeCause.ofEvent(event.eventId(), event.eventType())));
    }
}
