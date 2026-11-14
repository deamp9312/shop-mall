package com.example.shop.product;

public enum ProductSaleStatus {
    ON_SALE,
    /**
     * Hidden from the listing and cannot be put in a cart (REQ-F-013 1).
     *
     * 목록에서 숨기고 장바구니에 담을 수 없다 (REQ-F-013 1).
     */
    STOPPED
}
