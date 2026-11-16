package com.example.shop.payment;

/**
 * order.cancelled가 order.created보다 먼저 결제 그룹에 도착했다. 두 이벤트는 서로 다른 토픽이라 Kafka가 순서를
 * 보장하지 않는다. 레코드는 READY 결제가 생길 때까지 재시도(1s, 2s, 4s)되고, 그 뒤에야 취소된다.
 */
class PaymentNotPreparedException extends RuntimeException {

    PaymentNotPreparedException(long orderId) {
        super("no payment for order " + orderId + " yet");
    }
}
