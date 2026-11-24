package com.example.shop.payment;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.IdempotencyKeys;
import com.example.shop.common.IdempotencyScope;
import com.example.shop.common.ShopException;
import com.example.shop.common.TraceIds;
import com.example.shop.event.EventOutbox;
import com.example.shop.event.PaymentCompletedEvent;
import com.example.shop.event.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * mock PG 승인 (REQ-F-008 3, REQ-SE-002 3, 4). 실제 PG는 없으며, 사용자가 고른 결과가 승인 결과를 결정하고
 * 이를 outbox를 통해 payment.completed / payment.failed로 발행한다.
 * <p>
 * 이중 승인은 두 계층이 막는다. Redis 키 RedisKeys.idempotency(PAYMENT, orderId)는 반복 요청에 최초 결과로
 * 응답하고(처리 중이면 409), 결제 행은 아직 READY일 때만 갱신된다({@link Payment}).
 */
@Service
public class MockPgApprovalService {

    private static final Logger log = LoggerFactory.getLogger(MockPgApprovalService.class);

    private final PaymentRepository payments;
    private final EventOutbox outbox;
    private final IdempotencyKeys idempotencyKeys;
    private final TransactionTemplate transaction;
    private final TaskScheduler scheduler;
    private final Duration mockDelay;

    MockPgApprovalService(PaymentRepository payments, EventOutbox outbox, IdempotencyKeys idempotencyKeys,
                          TransactionTemplate transaction, TaskScheduler scheduler,
                          @Value("${shop.payment.mock-delay}") Duration mockDelay) {
        this.payments = payments;
        this.outbox = outbox;
        this.idempotencyKeys = idempotencyKeys;
        this.transaction = transaction;
        this.scheduler = scheduler;
        this.mockDelay = mockDelay;
    }

    /**
     * 주문이 해당 회원의 것인지는 호출자가 이미 확인했다.
     *
     * @return 승인 이후의 결제 상태. 지연 응답이 아직 오지 않았으면 READY
     * @throws ShopException PAYMENT_NOT_READY, PAYMENT_EXPIRED, PAYMENT_NOT_PAYABLE, REQUEST_IN_PROGRESS
     */
    public PaymentStatus approve(long orderId, MockPgResult result, PaymentMethod method) {
        Optional<String> earlierResult = idempotencyKeys.claim(IdempotencyScope.PAYMENT, orderId);
        if (earlierResult.isPresent()) {
            return PaymentStatus.valueOf(earlierResult.get());
        }
        if (result == MockPgResult.DELAY) {
            scheduler.schedule(() -> settleLater(orderId, method), Instant.now().plus(mockDelay));
            log.info("mock PG answers in {}: orderId={}", mockDelay, orderId);
            return PaymentStatus.READY;
        }
        return settleAndRemember(orderId, result == MockPgResult.SUCCESS, method);
    }

    /** mock PG의 지연 응답은 승인이다. */
    private void settleLater(long orderId, PaymentMethod method) {
        try {
            settleAndRemember(orderId, true, method);
        } catch (RuntimeException e) {
            log.warn("delayed mock PG approval not applied: orderId={} cause={}", orderId, e.getMessage());
        }
    }

    /** 결과를 멱등 키에 저장한다. 승인이 거부되면 사용자가 다시 시도할 수 있도록 키를 해제한다. */
    private PaymentStatus settleAndRemember(long orderId, boolean approved, PaymentMethod method) {
        String key = String.valueOf(orderId);
        try {
            PaymentStatus status = settle(orderId, approved, method);
            idempotencyKeys.complete(IdempotencyScope.PAYMENT, key, status.name());
            return status;
        } catch (RuntimeException refused) {
            idempotencyKeys.abandon(IdempotencyScope.PAYMENT, key);
            throw refused;
        }
    }

    private PaymentStatus settle(long orderId, boolean approved, PaymentMethod method) {
        try {
            return transaction.execute(status -> settleReadyPayment(findPayment(orderId), approved, method));
        } catch (ObjectOptimisticLockingFailureException concurrentChange) {
            // 다른 승인이나 취소가 먼저 행을 바꿔서 조건부 UPDATE에 일치하는 행이 0건이었다.
            return settledStatusOf(findPayment(orderId));
        }
    }

    /**
     * <pre>
     * [IN]  payment(id=11, orderId=7, amount=17800, status=READY), approved=true|false, method
     * [OUT] approved=true:  payments(id=11) READY → APPROVED, outbox_events INSERT → Kafka produce
     *         topic=shop.payment.completed, key="7",
     *         payload={"orderId":7,"paymentId":11,"amount":17800,"approvedAt":"2026-11-24T10:03:21"}
     *         → 받는 쪽: OrderEventListener / StockEventListener / NotificationEventListener .onPaymentCompleted
     *       approved=false: payments(id=11) READY → FAILED, outbox_events INSERT → Kafka produce
     *         topic=shop.payment.failed, key="7", payload={"orderId":7,"paymentId":11}
     *         → 받는 쪽: OrderEventListener / StockEventListener / NotificationEventListener .onPaymentFailed
     * </pre>
     */
    private PaymentStatus settleReadyPayment(Payment payment, boolean approved, PaymentMethod method) {
        if (payment.getStatus() != PaymentStatus.READY) {
            return settledStatusOf(payment);
        }
        if (payment.isExpiredAt(LocalDateTime.now())) {
            throw new ShopException(ErrorCode.PAYMENT_EXPIRED);
        }
        log.info("payment approval continues the order trace: traceId={}", payment.getTraceId());
        return TraceIds.callWithTraceId(payment.getTraceId(), () -> {
            if (approved) {
                payment.approve(method, LocalDateTime.now());
                outbox.record(new PaymentCompletedEvent(payment.getOrderId(), payment.getId(), payment.getAmount(),
                        payment.getApprovedAt()));
            } else {
                payment.fail();
                outbox.record(new PaymentFailedEvent(payment.getOrderId(), payment.getId()));
            }
            log.info("mock PG answered: status={} amount={}", payment.getStatus(), payment.getAmount());
            return payment.getStatus();
        });
    }

    /** 더 이상 READY가 아닌 결제: 앞선 승인의 결과이거나, 더는 결제할 수 없는 상태다. */
    private static PaymentStatus settledStatusOf(Payment payment) {
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new ShopException(ErrorCode.PAYMENT_NOT_PAYABLE);
        }
        return payment.getStatus();
    }

    private Payment findPayment(long orderId) {
        return payments.findByOrderId(orderId).orElseThrow(() -> new ShopException(ErrorCode.PAYMENT_NOT_READY));
    }
}
