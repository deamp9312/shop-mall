package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.MdcKeys;
import com.example.shop.common.RedisLock;
import com.example.shop.common.ShopException;
import com.example.shop.common.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 기한이 지난 주문을 취소해(REQ-F-010) 방치된 결제 화면이 재고를 붙잡고 있지 않게 한다.
 * 실행 주기는 shop.order.timeout-check-interval(1분)이고, 기한 자체는 shop.order.payment-timeout이다.
 * <p>
 * 두 인스턴스가 같은 스케줄로 실행하므로, 실행 중인 쪽이 Redis 락 RedisKeys.lock(LOCK_NAME)(SET NX PX 50 s)을 잡고
 * 다른 인스턴스는 건너뛴다. 락은 첫 번째 계층일 뿐이다. 각 주문은 상태 검사와 orders.version 낙관적 락으로
 * 취소되므로, 실행이 겹치더라도 order.cancelled를 두 번 발행할 수 없다.
 */
@Component
class PaymentTimeoutJob {

    static final String LOCK_NAME = "order-timeout";
    /** 1분 주기보다 짧고, 배치 한 번의 소요 시간보다 길다. */
    static final Duration LOCK_TTL = Duration.ofSeconds(50);
    static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutJob.class);

    private final OrderRepository orders;
    private final OrderCancellationService cancellation;
    private final RedisLock lock;

    PaymentTimeoutJob(OrderRepository orders, OrderCancellationService cancellation, RedisLock lock) {
        this.orders = orders;
        this.cancellation = cancellation;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${shop.order.timeout-check-interval}",
            initialDelayString = "${shop.order.timeout-check-interval}")
    void cancelOverdueOrders() {
        try {
            lock.runExclusively(LOCK_NAME, LOCK_TTL, ErrorCode.REQUEST_IN_PROGRESS, this::cancelOverdueBatch);
        } catch (ShopException lockHeldElsewhere) {
            log.debug("payment timeout check skipped: another instance holds the lock");
        }
    }

    private int cancelOverdueBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> overdue = orders.findPaymentOverdueIds(now, PageRequest.of(0, BATCH_SIZE));
        int cancelled = 0;
        for (Long orderId : overdue) {
            if (cancelInOwnTrace(orderId, now)) {
                cancelled++;
            }
        }
        if (!overdue.isEmpty()) {
            log.info("payment timeout check: overdue={} cancelled={}", overdue.size(), cancelled);
        }
        return cancelled;
    }

    /** 각 취소는 독립된 흐름을 시작하므로 자체 traceId를 부여받는다 (REQ-NF-003). */
    private boolean cancelInOwnTrace(long orderId, LocalDateTime now) {
        MDC.put(MdcKeys.ORDER_ID, String.valueOf(orderId));
        try {
            return TraceIds.callWithTraceId(TraceIds.newTraceId(),
                    () -> cancellation.cancelIfPaymentOverdue(orderId, now));
        } finally {
            MDC.remove(MdcKeys.ORDER_ID);
        }
    }
}
