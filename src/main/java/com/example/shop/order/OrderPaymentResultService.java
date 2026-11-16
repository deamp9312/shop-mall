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
 * 결제 결과를 주문에 반영한다 (REQ-F-009 2, 3). 결제 모듈은 주문을 직접 갱신하지 않으며, 주문 그룹이 결과를
 * 소비할 때 이 서비스가 갱신한다. 두 메서드 모두 멱등 컨슈머 트랜잭션에 참여한다.
 */
@Service
public class OrderPaymentResultService {

    /** 주문이 취소된 뒤에 결제가 성공했을 때의 PaymentCancelRequestedEvent.reason. */
    static final String PAID_AFTER_CANCEL = "ORDER_ALREADY_CANCELLED";

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentResultService.class);

    private final OrderRepository orders;
    private final EventOutbox outbox;

    OrderPaymentResultService(OrderRepository orders, EventOutbox outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /**
     * PAYMENT_PENDING -> PAID. 취소된 주문(타임아웃 또는 사용자 취소로, 결제가 너무 늦게 도착한 경우)은 조용히
     * 건너뛰지 않고 결제 모듈에 결제 취소를 요청한다 (REQ-F-009 3). 그 밖의 경우는 전이 테이블이 거부한다.
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

    /** PAYMENT_PENDING -> PAYMENT_FAILED. 취소된 주문은 할 일이 없다. 실패한 결제는 되돌려 줄 것이 없기 때문이다. */
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

    /** 존재하지 않는 주문에 대한 이벤트는 결코 성공할 수 없으므로, ShopException으로 재시도 없이 DLT로 보낸다. */
    private Order find(long orderId) {
        return orders.findForStatusChange(orderId).orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }
}
