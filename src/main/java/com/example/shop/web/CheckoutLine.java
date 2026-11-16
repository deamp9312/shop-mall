package com.example.shop.web;

/** 주문서의 한 라인. 금액은 표시용일 뿐이며 서버가 주문 금액을 다시 계산한다 (REQ-F-006). */
public record CheckoutLine(long productId, String productName, long unitPrice, int quantity) {

    public long subtotal() {
        return unitPrice * quantity;
    }
}
