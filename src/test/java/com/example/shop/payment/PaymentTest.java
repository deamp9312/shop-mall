package com.example.shop.payment;

import com.example.shop.common.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Test
    void readyPaymentCanBeApproved() {
        Payment payment = newPayment();

        payment.approve(PaymentMethod.CARD, APPROVED_AT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getApprovedAt()).isEqualTo(APPROVED_AT);
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD);
    }

    @Test
    void approvedPaymentCanBeCancelled() {
        Payment payment = newPayment();
        payment.approve(PaymentMethod.CARD, APPROVED_AT);

        payment.cancel();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAt()).isNotNull();
    }

    @Test
    void readyPaymentCanFailOrBeCancelled() {
        Payment failed = newPayment();
        failed.fail();
        Payment cancelled = newPayment();
        cancelled.cancel();

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(cancelled.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void approvingTwiceIsRejected() {
        Payment payment = newPayment();
        payment.approve(PaymentMethod.CARD, APPROVED_AT);

        assertThatThrownBy(() -> payment.approve(PaymentMethod.CARD, APPROVED_AT))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void failedPaymentIsTerminal() {
        Payment payment = newPayment();
        payment.fail();

        assertThatThrownBy(() -> payment.approve(PaymentMethod.CARD, APPROVED_AT))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(payment::cancel).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void cancelledPaymentCannotBeApproved() {
        Payment payment = newPayment();
        payment.cancel();

        assertThatThrownBy(() -> payment.approve(PaymentMethod.CARD, APPROVED_AT))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void transitionTableMatchesSpecification() {
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.READY)).isFalse();
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.APPROVED.canTransitionTo(target)).isEqualTo(target == PaymentStatus.CANCELLED);
            assertThat(PaymentStatus.FAILED.canTransitionTo(target)).isFalse();
            assertThat(PaymentStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void paymentExpiresExactlyAtItsDeadline() {
        Payment payment = newPayment();
        LocalDateTime deadline = payment.getExpiresAt();

        assertThat(payment.isExpiredAt(deadline.minusNanos(1_000))).isFalse();
        assertThat(payment.isExpiredAt(deadline)).isTrue();
    }

    private static Payment newPayment() {
        return Payment.ready(1L, 9_500L, APPROVED_AT.plusMinutes(15), "trace");
    }
}
