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
 *
 * 결제 결과를 주문에 반영한다 (REQ-F-009 2, 3). 결제 모듈은 주문을 직접 바꾸지 않는다. 주문 그룹이 결과를 소비할 때 이
 * 서비스가 바꾼다. 두 메서드 모두 멱등 컨슈머 트랜잭션에 참여한다.
 */
@Service
public class OrderPaymentResultService {

    /**
     * PaymentCancelRequestedEvent.reason when a payment succeeded after the order was cancelled.
     *
     * 주문이 취소된 뒤 결제가 성공했을 때의 PaymentCancelRequestedEvent.reason.
     */
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
     *
     * PAYMENT_PENDING -> PAID. 취소된 주문(시간 초과나 사용자 취소, 결제가 너무 늦게 도착)은 조용히 넘기지 않고 결제
     * 모듈에 결제 취소를 요청한다 (REQ-F-009 3). 그 밖의 경우는 전이표가 거부한다.
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

    /**
     * PAYMENT_PENDING -> PAYMENT_FAILED. A cancelled order needs nothing: a failed payment has nothing to give back.
     *
     * PAYMENT_PENDING -> PAYMENT_FAILED. 취소된 주문이면 할 일이 없다. 실패한 결제는 돌려줄 것이 없다.
     */
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

    /**
     * An event for an unknown order can never succeed, so ShopException sends it to the DLT without retries.
     *
     * 알 수 없는 주문의 이벤트는 절대 성공할 수 없으므로 ShopException으로 재시도 없이 DLT로 보낸다.
     */
    private Order find(long orderId) {
        return orders.findForStatusChange(orderId).orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }
}
