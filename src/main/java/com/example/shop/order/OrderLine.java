package com.example.shop.order;

/** 주문 생성에 쓰는 상품 한 줄. 가격은 주문 시점에 서버가 조회한 상품 가격이다. */
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
