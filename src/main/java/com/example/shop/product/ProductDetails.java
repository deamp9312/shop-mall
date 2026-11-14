package com.example.shop.product;

/**
 * Editable product information (REQ-F-013 1). Stock is not part of it.
 *
 * 수정할 수 있는 상품 정보 (REQ-F-013 1). 재고는 포함하지 않는다.
 */
public record ProductDetails(String name, String description, long price, String imageUrl) {
}
