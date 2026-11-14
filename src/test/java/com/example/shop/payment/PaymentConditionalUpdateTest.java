package com.example.shop.payment;

import com.example.shop.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-SE-002 4): the DB layer under the Redis key. An approval that read the payment as READY cannot overwrite a
 * cancellation committed in the meantime, because the UPDATE is conditional on the status it read.
 *
 * REQ-SE-002 4): Redis 키 아래의 DB 방어선. 결제를 READY로 읽은 승인은 그 사이 커밋된 취소를 덮어쓸 수 없다. UPDATE가
 * 읽은 상태를 조건으로 걸기 때문이다.
 */
class PaymentConditionalUpdateTest extends IntegrationTestSupport {

    private static final long ORDER_ID = 9_531L;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from payments where order_id = ?", ORDER_ID);
    }

    @Test
    void approvalBasedOnAStaleReadyRowFailsInsteadOfOverwritingTheCancellation() throws Exception {
        payments.save(Payment.ready(ORDER_ID, 5_000, LocalDateTime.now().plusMinutes(15), "trace"));
        CountDownLatch readReady = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);

        CompletableFuture<Void> approval = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            Payment payment = payments.findByOrderId(ORDER_ID).orElseThrow();
            readReady.countDown();
            awaitLatch(cancelled);
            payment.approve(PaymentMethod.CARD, LocalDateTime.now());
        }));
        assertThat(readReady.await(10, TimeUnit.SECONDS)).isTrue();
        transaction.executeWithoutResult(status -> payments.findByOrderId(ORDER_ID).orElseThrow().cancel());
        cancelled.countDown();

        assertThatThrownBy(() -> approval.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("select status from payments where order_id = ?", String.class, ORDER_ID))
                .isEqualTo("CANCELLED");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
