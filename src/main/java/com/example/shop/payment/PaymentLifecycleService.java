package com.example.shop.payment;

import com.example.shop.event.EventOutbox;
import com.example.shop.event.OrderCreatedEvent;
import com.example.shop.event.PaymentCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment changes driven by events (REQ-F-008 1, REQ-F-010 2, REQ-F-011 3, REQ-F-009 3). Every method joins the
 * idempotent consumer transaction of {@link PaymentEventListener}.
 *
 * 이벤트가 일으키는 결제 변경 (REQ-F-008 1, REQ-F-010 2, REQ-F-011 3, REQ-F-009 3). 모든 메서드가 {@link
 * PaymentEventListener}의 멱등 컨슈머 트랜잭션에 참여한다.
 */
@Service
public class PaymentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleService.class);

    private final PaymentRepository payments;
    private final EventOutbox outbox;

    PaymentLifecycleService(PaymentRepository payments, EventOutbox outbox) {
        this.payments = payments;
        this.outbox = outbox;
    }

    /**
     * order.created: a READY payment with the order's amount and deadline. Amounts come from the event, never the client.
     *
     * order.created: 주문의 금액과 기한으로 READY 결제를 만든다. 금액은 이벤트에서 가져오며 클라이언트 값을 쓰지
     * 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepare(OrderCreatedEvent order, String traceId) {
        if (payments.findByOrderId(order.orderId()).isPresent()) {
            log.info("payment already prepared");
            return;
        }
        payments.save(Payment.ready(order.orderId(), order.totalAmount(), order.expiresAt(), traceId));
        log.info("payment ready: amount={} expiresAt={}", order.totalAmount(), order.expiresAt());
    }

    /**
     * order.cancelled (timeout or user) and payment.cancel-requested: a READY payment is just cancelled; an APPROVED one
     * is refunded at the mock PG, cancelled and announced with payment.cancelled. FAILED or CANCELLED needs nothing.
     *
     * order.cancelled(시간 초과나 사용자 취소)와 payment.cancel-requested: READY 결제는 그냥 취소한다. APPROVED 결제는
     * 모의 PG에서 환불하고 취소한 뒤 payment.cancelled로 알린다. FAILED나 CANCELLED는 할 일이 없다.
     * 예외: order.created가 아직 처리되지 않았으면 PaymentNotPreparedException (재시도됨, 예외 클래스 참고)
     *
     * @throws PaymentNotPreparedException when order.created has not been handled yet (retried, see the exception)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForOrder(long orderId) {
        Payment payment = payments.findByOrderId(orderId).orElseThrow(() -> new PaymentNotPreparedException(orderId));
        switch (payment.getStatus()) {
            case READY -> {
                payment.cancel();
                log.info("payment cancelled before approval");
            }
            case APPROVED -> refund(payment);
            case FAILED, CANCELLED -> log.info("payment needs no cancellation: status={}", payment.getStatus());
        }
    }

    private void refund(Payment payment) {
        log.info("mock PG refund: paymentId={} amount={}", payment.getId(), payment.getAmount());
        payment.cancel();
        outbox.record(new PaymentCancelledEvent(payment.getOrderId(), payment.getId()));
        log.info("approved payment cancelled");
    }
}
