package com.example.shop.web;

/** One line of the order form; the amounts are for display only, the server prices the order again (REQ-F-006). */
public record CheckoutLine(long productId, String productName, long unitPrice, int quantity) {

    public long subtotal() {
        return unitPrice * quantity;
    }
}
