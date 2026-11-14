package com.example.shop.order;

import java.time.LocalDateTime;

/**
 * One row of the order list (REQ-F-012 1): "대표 상품명 외 N건".
 *
 * 주문 목록의 한 줄 (REQ-F-012 1): "대표 상품명 외 N건".
 */
public record OrderSummary(long orderId, LocalDateTime orderedAt, String representativeProductName,
                           int otherItemCount, long totalAmount, OrderStatus status) {

    static OrderSummary of(Order order) {
        var items = order.getItems();
        return new OrderSummary(order.getId(), order.getCreatedAt(), items.get(0).getProductName(), items.size() - 1,
                order.getTotalAmount(), order.getStatus());
    }
}
