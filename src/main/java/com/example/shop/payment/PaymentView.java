package com.example.shop.payment;

import java.time.LocalDateTime;

/** 다른 모듈에 공개해도 되는 결제 정보 (결제 화면, 주문 상세). */
public record PaymentView(PaymentStatus status, long amount, PaymentMethod method, LocalDateTime expiresAt,
                          LocalDateTime approvedAt, LocalDateTime cancelledAt) {

    static PaymentView of(Payment payment) {
        return new PaymentView(payment.getStatus(), payment.getAmount(), payment.getMethod(), payment.getExpiresAt(),
                payment.getApprovedAt(), payment.getCancelledAt());
    }
}
