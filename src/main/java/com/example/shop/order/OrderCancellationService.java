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
 * 주문을 취소하고 보상 처리를 시작한다 (REQ-F-010 2, REQ-F-011 2). 주문을 CANCELLED로 바꾸고 같은 트랜잭션에서
 * order.cancelled를 기록한다. 재고·결제 모듈은 이를 소비하면서 재고를 복원하고 결제를 취소한다.
 * <p>
 * 상태 검사 전에 주문 행을 잠그므로({@link OrderRepository#findForStatusChange}), 같은 주문에 대한 동시 변경(주문
 * 컨슈머의 PAID 처리, 다른 타임아웃 실행)은 대기한 뒤 그 결과를 보게 된다. 따라서 주문은 최대 한 번만 취소·발행된다.
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

    /** @throws ShopException ORDER_NOT_FOUND (다른 회원의 주문인 경우 포함), ORDER_NOT_CANCELLABLE */
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

    /** @return 이 호출이 주문을 취소했으면 true, 그사이 결제되었거나 이미 취소되었으면 false */
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
