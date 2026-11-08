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

    /** order.created: a READY payment with the order's amount and deadline. Amounts come from the event, never the client. */
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
