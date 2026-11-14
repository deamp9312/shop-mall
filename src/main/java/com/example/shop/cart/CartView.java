package com.example.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Cart screen / GET /api/cart body. The total counts only orderable lines (REQ-F-005 3).
 *
 * 장바구니 화면 / GET /api/cart 응답 본문. 합계에는 주문 가능한 줄만 포함한다 (REQ-F-005 3).
 */
public record CartView(List<CartLine> lines) {

    @JsonProperty
    public long totalAmount() {
        return lines.stream().filter(CartLine::orderable).mapToLong(CartLine::subtotal).sum();
    }
}
