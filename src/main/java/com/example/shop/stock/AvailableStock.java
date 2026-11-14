package com.example.shop.stock;

/**
 * Available stock of one product = DB stock - quantity still RESERVED.
 *
 * 상품 하나의 가용 재고 = DB 재고 - 아직 RESERVED인 수량.
 */
public record AvailableStock(Long productId, Long quantity) {
}
