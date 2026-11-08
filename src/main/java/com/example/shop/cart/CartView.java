package com.example.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Cart screen / GET /api/cart body. The total counts only orderable lines (REQ-F-005 3). */
public record CartView(List<CartLine> lines) {

    @JsonProperty
    public long totalAmount() {
        return lines.stream().filter(CartLine::orderable).mapToLong(CartLine::subtotal).sum();
    }
}
