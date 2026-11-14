package com.example.shop.payment;

import java.time.LocalDateTime;

/**
 * What other modules may see of a payment (payment screen, order detail).
 *
 * 다른 모듈이 볼 수 있는 결제 정보 (결제 화면, 주문 상세).
 */
public record PaymentView(PaymentStatus status, long amount, PaymentMethod method, LocalDateTime expiresAt,
                          LocalDateTime approvedAt, LocalDateTime cancelledAt) {

    static PaymentView of(Payment payment) {
        return new PaymentView(payment.getStatus(), payment.getAmount(), payment.getMethod(), payment.getExpiresAt(),
                payment.getApprovedAt(), payment.getCancelledAt());
    }
}
