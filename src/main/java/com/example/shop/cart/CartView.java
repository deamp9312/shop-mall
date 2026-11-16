package com.example.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 장바구니 화면 / GET /api/cart 본문. 합계는 주문 가능한 라인만 더한다 (REQ-F-005 3). */
public record CartView(List<CartLine> lines) {

    @JsonProperty
    public long totalAmount() {
        return lines.stream().filter(CartLine::orderable).mapToLong(CartLine::subtotal).sum();
    }
}
