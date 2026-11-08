package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of orders: order list and detail screens (REQ-F-012) and the notification module's member lookup. */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    public static final int PAGE_SIZE = 10;

    private final OrderRepository orders;

    OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    /** @param page zero-based */
    public Page<OrderSummary> findSummaries(long memberId, int page) {
        return orders.findByMemberIdOrderByCreatedAtDescIdDesc(memberId, PageRequest.of(Math.max(page, 0), PAGE_SIZE))
                .map(OrderSummary::of);
    }

    /**
     * The order with its lines and status history loaded, for the detail page (open-in-view is off).
     *
     * @throws ShopException ORDER_NOT_FOUND, also for another member's order (REQ-F-012: existence is not revealed)
     */
    public Order findDetail(long memberId, long orderId) {
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
        order.getItems().size();
        order.getStatusHistory().size();
        return order;
    }

    /** @throws ShopException ORDER_NOT_FOUND */
    public long memberIdOf(long orderId) {
        return orders.findById(orderId).map(Order::getMemberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }
}
