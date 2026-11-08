package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import com.example.shop.event.EventOutbox;
import com.example.shop.event.PaymentCancelRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Applies payment results to orders (REQ-F-009 2, 3). The payment module never updates orders; this service does it
 * when the order group consumes the result. Both methods join the idempotent consumer transaction.
 */
@Service
public class OrderPaymentResultService {

    /** PaymentCancelRequestedEvent.reason when a payment succeeded after the order was cancelled. */
    static final String PAID_AFTER_CANCEL = "ORDER_ALREADY_CANCELLED";

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentResultService.class);

    private final OrderRepository orders;
    private final EventOutbox outbox;

    OrderPaymentResultService(OrderRepository orders, EventOutbox outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /**
     * PAYMENT_PENDING -> PAID. A cancelled order (timeout or user, the payment arrived too late) is not silently
     * skipped: it asks the payment module to cancel the payment (REQ-F-009 3). The transition table rejects anything else.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyPaymentCompleted(long orderId, LocalDateTime approvedAt, StatusChangeCause cause) {
        Order order = find(orderId);
        if (order.isCancelled()) {
            outbox.record(new PaymentCancelRequestedEvent(orderId, PAID_AFTER_CANCEL));
            log.warn("payment completed for a cancelled order: payment cancellation requested");
            return;
        }
        order.markPaid(approvedAt, cause);
        log.info("order paid: status={}", order.getStatus());
    }

    /** PAYMENT_PENDING -> PAYMENT_FAILED. A cancelled order needs nothing: a failed payment has nothing to give back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyPaymentFailed(long orderId, StatusChangeCause cause) {
        Order order = find(orderId);
        if (order.isCancelled()) {
            log.info("payment failed for a cancelled order: nothing to do");
            return;
        }
        order.markPaymentFailed(cause);
        log.info("order payment failed: status={}", order.getStatus());
    }

    /** An event for an unknown order can never succeed, so ShopException sends it to the DLT without retries. */
    private Order find(long orderId) {
        return orders.findForStatusChange(orderId).orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }
}
