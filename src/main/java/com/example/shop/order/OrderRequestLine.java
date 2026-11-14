package com.example.shop.order;

/**
 * A product and quantity the member wants to order. Price and name are looked up on the server (REQ-F-006).
 *
 * 회원이 주문하려는 상품과 수량. 가격과 이름은 서버에서 조회한다 (REQ-F-006).
 */
public record OrderRequestLine(long productId, int quantity) {
}
