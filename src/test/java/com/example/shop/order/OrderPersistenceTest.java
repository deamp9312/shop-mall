package com.example.shop.order;

import com.example.shop.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-DA-001: each status change is stored as one order_status_history row together with the order. */
class OrderPersistenceTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orders;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void statusChangesAreStoredAsHistoryRows() {
        Long orderId = transaction.execute(status -> orders.save(Order.place(1L, "test-key-0001",
                new ShippingInfo("Receiver", "010-0000-0000", "Somewhere 1"),
                List.of(new OrderLine(1L, "Plain Mug", 1_000, 2)),
                LocalDateTime.now().plusMinutes(15))).getId());

        transaction.executeWithoutResult(status -> {
            Order order = orders.findById(orderId).orElseThrow();
            order.markPaid(LocalDateTime.now(), StatusChangeCause.ofEvent("e-1", "PaymentCompleted"));
            order.cancel(CancelReason.USER_CANCEL);
        });

        List<String> history = jdbc.queryForList(
                "select concat(coalesce(from_status, '-'), '>', to_status) from order_status_history"
                        + " where order_id = ? order by id", String.class, orderId);
        assertThat(history).containsExactly("->PAYMENT_PENDING", "PAYMENT_PENDING>PAID", "PAID>CANCELLED");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select total_amount from orders where id = ?", Long.class, orderId))
                .isEqualTo(2_000L);
    }
}
