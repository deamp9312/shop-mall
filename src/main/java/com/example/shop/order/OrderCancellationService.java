package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import com.example.shop.event.EventOutbox;
import com.example.shop.event.OrderCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Cancels orders and starts the compensation (REQ-F-010 2, REQ-F-011 2): the order becomes CANCELLED and order.cancelled
 * is recorded in the same transaction; the stock and payment modules restore stock and cancel the payment when they
 * consume it.
 * <p>
 * The order row is locked before the status check ({@link OrderRepository#findForStatusChange}), so a concurrent
 * change of the same order (the order consumer marking it PAID, another timeout run) waits and then sees the result:
 * an order is cancelled and announced at most once.
 */
@Service
public class OrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

    private final OrderRepository orders;
    private final EventOutbox outbox;
    private final TransactionTemplate transaction;

    OrderCancellationService(OrderRepository orders, EventOutbox outbox, TransactionTemplate transaction) {
        this.orders = orders;
        this.outbox = outbox;
        this.transaction = transaction;
    }

    /** @throws ShopException ORDER_NOT_FOUND (also for another member's order), ORDER_NOT_CANCELLABLE */
    public void cancelByMember(long memberId, long orderId) {
        transaction.executeWithoutResult(status -> {
            Order order = orders.findForStatusChange(orderId)
                    .filter(found -> found.getMemberId() == memberId)
                    .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
            if (!order.isCancellable()) {
                throw new ShopException(ErrorCode.ORDER_NOT_CANCELLABLE);
            }
            cancel(order, CancelReason.USER_CANCEL);
        });
    }

    /** @return true when this call cancelled the order; false when it was paid or cancelled meanwhile */
    public boolean cancelIfPaymentOverdue(long orderId, LocalDateTime now) {
        Boolean cancelled = transaction.execute(status -> orders.findForStatusChange(orderId)
                .filter(order -> order.isPaymentOverdueAt(now))
                .map(order -> {
                    cancel(order, CancelReason.PAYMENT_TIMEOUT);
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(cancelled);
    }

    private void cancel(Order order, CancelReason reason) {
        order.cancel(reason);
        outbox.record(new OrderCancelledEvent(order.getId(), order.getMemberId(), reason.name()));
        log.info("order cancelled: reason={}", reason);
    }
}
