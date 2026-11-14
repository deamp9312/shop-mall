package com.example.shop.cart;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One cart line priced from the current product information (REQ-F-005: prices are never stored in the cart).
 * A line is orderable only when the product is on sale and enough stock is available.
 *
 * 현재 상품 정보로 가격을 매긴 장바구니 한 줄 (REQ-F-005: 장바구니에는 가격을 저장하지 않는다).
 * 상품이 판매 중이고 재고가 충분할 때만 주문할 수 있는 줄이다.
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
