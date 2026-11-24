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
 * 이벤트로 일어나는 결제 변경 (REQ-F-008 1, REQ-F-010 2, REQ-F-011 3, REQ-F-009 3). 모든 메서드는
 * {@link PaymentEventListener}의 멱등 컨슈머 트랜잭션에 참여한다.
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

    /** order.created: 주문 금액과 기한으로 READY 결제를 만든다. 금액은 클라이언트가 아니라 항상 이벤트에서 가져온다. */
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
     * order.cancelled(타임아웃 또는 사용자 취소)와 payment.cancel-requested: READY 결제는 취소만 하고, APPROVED 결제는
     * mock PG에서 환불한 뒤 취소하고 payment.cancelled로 알린다. FAILED나 CANCELLED는 할 일이 없다.
     *
     * @throws PaymentNotPreparedException order.created가 아직 처리되지 않았을 때 (재시도된다. 예외 클래스 참고)
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

    /**
     * <pre>
     * [IN]  payment(id=11, orderId=7, amount=17800, status=APPROVED)
     * [OUT] payments(id=11).status → CANCELLED
     *       outbox_events INSERT → Kafka produce
     *         topic=shop.payment.cancelled, key="7", payload={"orderId":7,"paymentId":11}
     *       → 받는 쪽: NotificationEventListener.onPaymentCancelled (shop-notification)
     * </pre>
     */
    private void refund(Payment payment) {
        log.info("mock PG refund: paymentId={} amount={}", payment.getId(), payment.getAmount());
        payment.cancel();
        outbox.record(new PaymentCancelledEvent(payment.getOrderId(), payment.getId()));
        log.info("approved payment cancelled");
    }
}
