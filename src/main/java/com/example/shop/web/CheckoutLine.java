package com.example.shop.web;

/**
 * One line of the order form; the amounts are for display only, the server prices the order again (REQ-F-006).
 *
 * 주문서의 한 줄. 금액은 표시용일 뿐이며 서버가 주문 가격을 다시 계산한다 (REQ-F-006).
 */
public record CheckoutLine(long productId, String productName, long unitPrice, int quantity) {

    public long subtotal() {
        return unitPrice * quantity;
    }
}
