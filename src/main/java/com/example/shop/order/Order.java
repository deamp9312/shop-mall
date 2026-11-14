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
 *
 * 주문 애그리거트. 상태는 아래 메서드로만 바뀐다. 각 메서드는 전이표를 검사하고 order_status_history 행을 정확히 하나
 * 추가한다 (REQ-DA-001).
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /**
     * Idempotency-Key of the request that created the order; unique per member (REQ-SE-002, second layer).
     *
     * 주문을 만든 요청의 Idempotency-Key. 회원별로 유일하다 (REQ-SE-002, 두 번째 방어선).
     */
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

    /**
     * Batch fetch keeps the order list page (10 orders, REQ-F-012 1) at one items query instead of ten.
     *
     * 배치 fetch로 주문 목록 화면(주문 10건, REQ-F-012 1)의 상품 조회를 10번이 아닌 1번으로 줄인다.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @BatchSize(size = 10)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("changedAt ASC, id ASC")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    protected Order() {
    }

    /**
     * Creates a PAYMENT_PENDING order whose total is calculated from the lines, never taken from the client.
     *
     * PAYMENT_PENDING 주문을 만든다. 합계는 주문 줄로 계산하며 클라이언트 값을 받지 않는다.
     */
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

    /**
     * REQ-F-011 1): PAYMENT_PENDING and PAID orders can be cancelled.
     *
     * REQ-F-011 1): PAYMENT_PENDING과 PAID 주문은 취소할 수 있다.
     */
    public boolean isCancellable() {
        return status.canTransitionTo(OrderStatus.CANCELLED);
    }

    public boolean isCancelled() {
        return status == OrderStatus.CANCELLED;
    }

    /**
     * REQ-F-010 1): still waiting for payment when the deadline has passed.
     *
     * REQ-F-010 1): 기한이 지났는데 아직 결제를 기다리는 상태.
     */
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
