package com.example.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One cart line priced from the current product information (REQ-F-005: prices are never stored in the cart).
 * A line is orderable only when the product is on sale and enough stock is available.
 */
public record CartLine(long productId, String productName, String imageUrl, long unitPrice, int quantity,
                       long availableStock, boolean onSale) {

    @JsonProperty
    public long subtotal() {
        return unitPrice * quantity;
    }

    @JsonProperty
    public boolean soldOut() {
        return availableStock <= 0;
    }

    @JsonProperty
    public boolean orderable() {
        return onSale && availableStock >= quantity;
    }
}
