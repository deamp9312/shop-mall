package com.example.shop.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 다른 모듈을 위한 결제 읽기 전용 조회 (상태 폴링 REQ-IF-001 3, 결제 화면, 주문 상세). */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository payments;

    public PaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    /** 결제 모듈이 order.created를 처리해 READY 결제를 만들기 전까지는 비어 있다. */
    public Optional<PaymentStatus> findStatus(long orderId) {
        return payments.findByOrderId(orderId).map(Payment::getStatus);
    }

    public Optional<PaymentView> findPayment(long orderId) {
        return payments.findByOrderId(orderId).map(PaymentView::of);
    }
}
