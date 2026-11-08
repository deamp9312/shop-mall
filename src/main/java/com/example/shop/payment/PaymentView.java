package com.example.shop.payment;

import java.time.LocalDateTime;

/** What other modules may see of a payment (payment screen, order detail). */
public record PaymentView(PaymentStatus status, long amount, PaymentMethod method, LocalDateTime expiresAt,
                          LocalDateTime approvedAt, LocalDateTime cancelledAt) {

    static PaymentView of(Payment payment) {
        return new PaymentView(payment.getStatus(), payment.getAmount(), payment.getMethod(), payment.getExpiresAt(),
                payment.getApprovedAt(), payment.getCancelledAt());
    }
}
