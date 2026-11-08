package com.example.shop.payment;

/**
 * order.cancelled reached the payment group before order.created. The two are on different topics, so Kafka does not
 * order them; the record is retried (1s, 2s, 4s) until the READY payment exists, and only then cancelled.
 */
class PaymentNotPreparedException extends RuntimeException {

    PaymentNotPreparedException(long orderId) {
        super("no payment for order " + orderId + " yet");
    }
}
