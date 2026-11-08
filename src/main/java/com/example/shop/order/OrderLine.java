package com.example.shop.order;

/** One product line used to place an order. The price is the server-side product price at order time. */
public record OrderLine(long productId, String productName, long unitPrice, int quantity) {

    public OrderLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + unitPrice);
        }
    }

    long lineAmount() {
        return unitPrice * quantity;
    }
}
