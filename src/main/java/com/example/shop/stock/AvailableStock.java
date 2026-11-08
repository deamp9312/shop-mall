package com.example.shop.stock;

/** Available stock of one product = DB stock - quantity still RESERVED. */
public record AvailableStock(Long productId, Long quantity) {
}
