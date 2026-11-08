package com.example.shop.order;

/** A product and quantity the member wants to order. Price and name are looked up on the server (REQ-F-006). */
public record OrderRequestLine(long productId, int quantity) {
}
