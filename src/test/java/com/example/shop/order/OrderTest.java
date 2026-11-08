package com.example.shop.order;

import com.example.shop.common.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class OrderTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final StatusChangeCause PAYMENT_COMPLETED =
            StatusChangeCause.ofEvent("e-completed", "PaymentCompleted");
    private static final StatusChangeCause PAYMENT_FAILED =
            StatusChangeCause.ofEvent("e-failed", "PaymentFailed");

    @Test
    void placedOrderIsPendingWithServerCalculatedTotalAndOneHistoryRow() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getTotalAmount()).isEqualTo(2 * 1_000 + 3 * 2_500);
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getStatusHistory()).singleElement().satisfies(history -> {
            assertThat(history.getFromStatus()).isNull();
            assertThat(history.getToStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        });
    }

    @Test
    void placingOrderWithoutLinesIsRejected() {
        assertThatThrownBy(() -> Order.place(1L, "test-key-0001", shipping(), List.of(), PAID_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paidRecordsPaidAtAndCausingEvent() {
        Order order = newOrder();

        order.markPaid(PAID_AT, PAYMENT_COMPLETED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(order.getStatusHistory()).hasSize(2).last().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(history.getToStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(history.getEventId()).isEqualTo("e-completed");
            assertThat(history.getEventType()).isEqualTo("PaymentCompleted");
        });
    }

    @Test
    void cancelAfterPaymentIsAllowedAndKeepsReason() {
        Order order = newOrder();
        order.markPaid(PAID_AT, PAYMENT_COMPLETED);

        order.cancel(CancelReason.USER_CANCEL);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(CancelReason.USER_CANCEL);
        assertThat(order.getStatusHistory()).extracting(OrderStatusHistory::getToStatus)
                .containsExactly(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory().get(2).getReason()).isEqualTo("USER_CANCEL");
    }

    @Test
    void paymentFailureMovesPendingOrderToPaymentFailed() {
        Order order = newOrder();

        order.markPaymentFailed(PAYMENT_FAILED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getStatusHistory()).hasSize(2);
    }

    static Stream<Arguments> forbiddenTransitions() {
        Consumer<Order> pay = order -> order.markPaid(PAID_AT, PAYMENT_COMPLETED);
        Consumer<Order> fail = order -> order.markPaymentFailed(PAYMENT_FAILED);
        Consumer<Order> cancel = order -> order.cancel(CancelReason.PAYMENT_TIMEOUT);
        return Stream.of(
                arguments("CANCELLED -> PAID", cancel, pay),
                arguments("CANCELLED -> PAYMENT_FAILED", cancel, fail),
                arguments("CANCELLED -> CANCELLED", cancel, cancel),
                arguments("PAYMENT_FAILED -> PAID", fail, pay),
                arguments("PAYMENT_FAILED -> CANCELLED", fail, cancel),
                arguments("PAYMENT_FAILED -> PAYMENT_FAILED", fail, fail),
                arguments("PAID -> PAID", pay, pay),
                arguments("PAID -> PAYMENT_FAILED", pay, fail));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenTransitions")
    void forbiddenTransitionThrowsAndLeavesOrderUntouched(String name, Consumer<Order> reachState,
                                                          Consumer<Order> forbiddenTransition) {
        Order order = newOrder();
        reachState.accept(order);
        OrderStatus before = order.getStatus();
        int historySize = order.getStatusHistory().size();

        assertThatThrownBy(() -> forbiddenTransition.accept(order))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(before);
        assertThat(order.getStatusHistory()).hasSize(historySize);
    }

    @Test
    void transitionTableMatchesSpecification() {
        assertThat(OrderStatus.PAYMENT_PENDING.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAYMENT_PENDING.canTransitionTo(OrderStatus.PAYMENT_FAILED)).isTrue();
        assertThat(OrderStatus.PAYMENT_PENDING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAYMENT_PENDING.canTransitionTo(OrderStatus.PAYMENT_PENDING)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.PAYMENT_FAILED.canTransitionTo(target)).isFalse();
            assertThat(OrderStatus.CANCELLED.canTransitionTo(target)).isFalse();
            assertThat(OrderStatus.PAID.canTransitionTo(target)).isEqualTo(target == OrderStatus.CANCELLED);
        }
    }

    @Test
    void pendingAndPaidOrdersAreCancellableOthersAreNot() {
        Order pending = newOrder();
        Order paid = newOrder();
        paid.markPaid(PAID_AT, PAYMENT_COMPLETED);
        Order failed = newOrder();
        failed.markPaymentFailed(PAYMENT_FAILED);
        Order cancelled = newOrder();
        cancelled.cancel(CancelReason.USER_CANCEL);

        assertThat(pending.isCancellable()).isTrue();
        assertThat(paid.isCancellable()).isTrue();
        assertThat(failed.isCancellable()).isFalse();
        assertThat(cancelled.isCancellable()).isFalse();
        assertThat(cancelled.isCancelled()).isTrue();
    }

    @Test
    void onlyAPendingOrderPastItsDeadlineIsPaymentOverdue() {
        Order pending = newOrder();
        LocalDateTime deadline = pending.getPaymentExpiresAt();
        Order paid = newOrder();
        paid.markPaid(PAID_AT, PAYMENT_COMPLETED);

        assertThat(pending.isPaymentOverdueAt(deadline.minusNanos(1_000))).isFalse();
        assertThat(pending.isPaymentOverdueAt(deadline)).isTrue();
        assertThat(paid.isPaymentOverdueAt(deadline.plusHours(1))).isFalse();
    }

    private static Order newOrder() {
        List<OrderLine> lines = List.of(
                new OrderLine(10L, "Plain Mug", 1_000, 2),
                new OrderLine(11L, "Notebook", 2_500, 3));
        return Order.place(1L, "test-key-0001", shipping(), lines, PAID_AT.plusMinutes(15));
    }

    private static ShippingInfo shipping() {
        return new ShippingInfo("Receiver", "010-0000-0000", "Somewhere 1");
    }
}
