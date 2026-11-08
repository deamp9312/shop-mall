package com.example.shop.order;

import com.example.shop.common.InvalidStateTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Order aggregate. Status changes only through the methods below; each one checks the transition table and appends
 * exactly one order_status_history row (REQ-DA-001).
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** Idempotency-Key of the request that created the order; unique per member (REQ-SE-002, second layer). */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Embedded
    private ShippingInfo shipping;

    @Column(name = "payment_expires_at", nullable = false)
    private LocalDateTime paymentExpiresAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cancel_reason", length = 30)
    private CancelReason cancelReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** Batch fetch keeps the order list page (10 orders, REQ-F-012 1) at one items query instead of ten. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @BatchSize(size = 10)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("changedAt ASC, id ASC")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    protected Order() {
    }

    /** Creates a PAYMENT_PENDING order whose total is calculated from the lines, never taken from the client. */
    public static Order place(Long memberId, String idempotencyKey, ShippingInfo shipping, List<OrderLine> lines,
                              LocalDateTime paymentExpiresAt) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        Order order = new Order();
        order.memberId = memberId;
        order.idempotencyKey = idempotencyKey;
        order.shipping = shipping;
        order.paymentExpiresAt = paymentExpiresAt;
        order.createdAt = LocalDateTime.now();
        order.updatedAt = order.createdAt;
        for (OrderLine line : lines) {
            order.items.add(new OrderItem(order, line));
            order.totalAmount += line.lineAmount();
        }
        order.status = OrderStatus.PAYMENT_PENDING;
        order.statusHistory.add(new OrderStatusHistory(order, null, OrderStatus.PAYMENT_PENDING,
                StatusChangeCause.orderPlaced()));
        return order;
    }

    public void markPaid(LocalDateTime paidAt, StatusChangeCause cause) {
        changeStatus(OrderStatus.PAID, cause);
        this.paidAt = paidAt;
    }

    public void markPaymentFailed(StatusChangeCause cause) {
        changeStatus(OrderStatus.PAYMENT_FAILED, cause);
    }

    public void cancel(CancelReason reason) {
        changeStatus(OrderStatus.CANCELLED, StatusChangeCause.cancelled(reason));
        this.cancelReason = reason;
    }

    /** REQ-F-011 1): PAYMENT_PENDING and PAID orders can be cancelled. */
    public boolean isCancellable() {
        return status.canTransitionTo(OrderStatus.CANCELLED);
    }

    public boolean isCancelled() {
        return status == OrderStatus.CANCELLED;
    }

    /** REQ-F-010 1): still waiting for payment when the deadline has passed. */
    public boolean isPaymentOverdueAt(LocalDateTime now) {
        return status == OrderStatus.PAYMENT_PENDING && !now.isBefore(paymentExpiresAt);
    }

    private void changeStatus(OrderStatus target, StatusChangeCause cause) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("Order", id, status, target);
        }
        statusHistory.add(new OrderStatusHistory(this, status, target, cause));
        status = target;
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public ShippingInfo getShipping() {
        return shipping;
    }

    public LocalDateTime getPaymentExpiresAt() {
        return paymentExpiresAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }
}
