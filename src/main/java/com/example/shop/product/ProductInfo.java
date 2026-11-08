package com.example.shop.product;

/**
 * Product information as cached under RedisKeys.product(id) (REQ-F-004). It deliberately has no stock: available stock
 * is read from Redis on every request.
 */
public record ProductInfo(Long id, Long categoryId, String categoryName, String name, String description,
                          long price, String imageUrl, ProductSaleStatus saleStatus) {

    static ProductInfo from(Product product) {
        return new ProductInfo(product.getId(), product.getCategory().getId(), product.getCategory().getName(),
                product.getName(), product.getDescription(), product.getPrice(), product.getImageUrl(),
                product.getSaleStatus());
    }

    public boolean onSale() {
        return saleStatus == ProductSaleStatus.ON_SALE;
    }
}
