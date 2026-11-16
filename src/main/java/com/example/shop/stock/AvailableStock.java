package com.example.shop.stock;

/** 한 상품의 가용 재고 = DB 재고 - 아직 RESERVED인 수량. */
public record AvailableStock(Long productId, Long quantity) {
}
