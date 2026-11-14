package com.example.shop.order;

/**
 * One product line used to place an order. The price is the server-side product price at order time.
 *
 * 주문할 때 쓰는 상품 한 줄. 가격은 주문 시점의 서버 쪽 상품 가격이다.
 */
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
