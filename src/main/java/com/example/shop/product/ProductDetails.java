package com.example.shop.product;

/** Editable product information (REQ-F-013 1). Stock is not part of it. */
public record ProductDetails(String name, String description, long price, String imageUrl) {
}
