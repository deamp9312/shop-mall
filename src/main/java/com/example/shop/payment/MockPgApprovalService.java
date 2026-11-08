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
 * Mock PG approval (REQ-F-008 3, REQ-SE-002 3, 4). There is no real PG: the result the user picked decides the outcome,
 * which is published as payment.completed / payment.failed through the outbox.
 * <p>
 * Two layers stop a double approval: the Redis key RedisKeys.idempotency(PAYMENT, orderId) answers a repeat with the
 * first result (or 409 while it runs), and the payment row is updated only while it is still READY ({@link Payment}).
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
     * The caller has already checked that the order belongs to the member.
     *
     * @return the payment status after the approval; READY when the delayed answer is still pending
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

    /** The delayed answer of the mock PG is an approval. */
    private void settleLater(long orderId, PaymentMethod method) {
        try {
            settleAndRemember(orderId, true, method);
        } catch (RuntimeException e) {
            log.warn("delayed mock PG approval not applied: orderId={} cause={}", orderId, e.getMessage());
        }
    }

    /** Stores the result in the idempotency key; a refused approval frees the key so the user can try again. */
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
            // Another approval or a cancellation changed the row first; the conditional UPDATE matched 0 rows.
            return settledStatusOf(findPayment(orderId));
        }
    }

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

    /** A payment that is no longer READY: an earlier approval's result, or not payable any more. */
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
