package com.example.shop.order;

/** 회원이 주문하려는 상품과 수량. 가격과 상품명은 서버에서 조회한다 (REQ-F-006). */
public record OrderRequestLine(long productId, int quantity) {
}
