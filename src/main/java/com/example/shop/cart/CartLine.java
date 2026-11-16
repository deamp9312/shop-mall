package com.example.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 현재 상품 정보로 가격을 매긴 장바구니 라인 하나 (REQ-F-005: 장바구니에는 가격을 저장하지 않는다).
 * 상품이 판매 중이고 가용 재고가 충분할 때만 주문할 수 있다.
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
