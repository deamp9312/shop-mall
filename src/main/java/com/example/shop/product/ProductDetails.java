package com.example.shop.product;

/** 수정 가능한 상품 정보(REQ-F-013 1). 재고는 포함하지 않는다. */
public record ProductDetails(String name, String description, long price, String imageUrl) {
}
