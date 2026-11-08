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

/** Stock module consumer (group shop-stock): confirm on payment success, release on payment failure or cancel. */
@Component
class StockEventListener {

    private final IdempotentEventProcessor processor;
    private final StockReservationService reservations;

    StockEventListener(IdempotentEventProcessor processor, StockReservationService reservations) {
        this.processor = processor;
        this.reservations = reservations;
    }

    @KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.STOCK)
    void onPaymentCompleted(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.STOCK, record, PaymentCompletedEvent.class,
                event -> reservations.confirm(event.payload().orderId()));
    }

    @KafkaListener(topics = EventTopics.PAYMENT_FAILED, groupId = ConsumerGroups.STOCK)
    void onPaymentFailed(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.STOCK, record, PaymentFailedEvent.class,
                event -> reservations.releaseReserved(event.payload().orderId()));
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = ConsumerGroups.STOCK)
    void onOrderCancelled(ConsumerRecord<String, String> record) {
        processor.process(ConsumerGroups.STOCK, record, OrderCancelledEvent.class,
                event -> reservations.releaseAll(event.payload().orderId()));
    }
}
