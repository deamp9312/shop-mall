package com.example.shop.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Read-only view of payments for other modules (status polling REQ-IF-001 3, payment screen, order detail). */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository payments;

    public PaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Empty until the payment module has handled order.created and created the READY payment. */
    public Optional<PaymentStatus> findStatus(long orderId) {
        return payments.findByOrderId(orderId).map(Payment::getStatus);
    }

    public Optional<PaymentView> findPayment(long orderId) {
        return payments.findByOrderId(orderId).map(PaymentView::of);
    }
}
