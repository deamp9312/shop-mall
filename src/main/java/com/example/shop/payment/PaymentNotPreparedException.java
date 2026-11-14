package com.example.shop.payment;

/**
 * order.cancelled reached the payment group before order.created. The two are on different topics, so Kafka does not
 * order them; the record is retried (1s, 2s, 4s) until the READY payment exists, and only then cancelled.
 *
 * order.cancelled가 order.created보다 먼저 결제 그룹에 도착했다. 두 이벤트는 다른 토픽이라 Kafka가 순서를 보장하지
 * 않는다. READY 결제가 생길 때까지 레코드를 재시도하고(1초, 2초, 4초), 그 뒤에야 취소한다.
 */
class PaymentNotPreparedException extends RuntimeException {

    PaymentNotPreparedException(long orderId) {
        super("no payment for order " + orderId + " yet");
    }
}
