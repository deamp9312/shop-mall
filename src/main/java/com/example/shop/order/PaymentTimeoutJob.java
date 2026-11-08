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
 * Cancels orders whose payment deadline passed (REQ-F-010), so an abandoned payment screen does not hold stock.
 * The interval is shop.order.timeout-check-interval (1 minute); the deadline itself is shop.order.payment-timeout.
 * <p>
 * Two instances run it on the same schedule, so the run holds the Redis lock RedisKeys.lock(LOCK_NAME) (SET NX PX 50 s)
 * and the other instance skips. The lock is the first layer only: each order is cancelled with a status check and the
 * orders.version optimistic lock, so even an overlapping run cannot publish order.cancelled twice.
 */
@Component
class PaymentTimeoutJob {

    static final String LOCK_NAME = "order-timeout";
    /** Shorter than the 1 minute interval, longer than one batch takes. */
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

    /** Each cancellation starts its own flow, so it gets its own traceId (REQ-NF-003). */
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
