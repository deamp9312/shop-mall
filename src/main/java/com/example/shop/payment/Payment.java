package com.example.shop.payment;

import com.example.shop.common.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Mock payment of one order. Status changes only through the methods below (REQ-DA-001).
 * <p>
 * Optimistic locking on the dirty columns makes Hibernate write every change as a conditional UPDATE: approving issues
 * {@code update payments set status='APPROVED', ... where id=? and status='READY'}. If a concurrent approval or
 * cancellation moved the row first, 0 rows match and the transaction fails instead of approving twice
 * (REQ-SE-002 4), the DB layer under the Redis idempotency key).
 */
@Entity
@Table(name = "payments")
@DynamicUpdate
@OptimisticLocking(type = OptimisticLockType.DIRTY)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private PaymentMethod method;

    /** traceId of the order flow; the approval continues it (REQ-NF-003). */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Payment() {
    }

    public static Payment ready(Long orderId, long amount, LocalDateTime expiresAt, String traceId) {
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.amount = amount;
        payment.expiresAt = expiresAt;
        payment.traceId = traceId;
        payment.status = PaymentStatus.READY;
        payment.createdAt = LocalDateTime.now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    /** @param approvedAt approval time reported by the mock PG */
    public void approve(PaymentMethod method, LocalDateTime approvedAt) {
        changeStatus(PaymentStatus.APPROVED);
        this.method = method;
        this.approvedAt = approvedAt;
    }

    public void fail() {
        changeStatus(PaymentStatus.FAILED);
        this.failedAt = updatedAt;
    }

    public void cancel() {
        changeStatus(PaymentStatus.CANCELLED);
        this.cancelledAt = updatedAt;
    }

    /** The mock PG accepts an approval only before the order's payment deadline (REQ-F-008). */
    public boolean isExpiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    private void changeStatus(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("Payment", id, status, target);
        }
        status = target;
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getTraceId() {
        return traceId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public LocalDateTime getFailedAt() {
        return failedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }
}
