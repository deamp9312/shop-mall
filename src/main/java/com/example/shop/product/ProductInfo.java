package com.example.shop.product;

/**
 * Product information as cached under RedisKeys.product(id) (REQ-F-004). It deliberately has no stock: available stock
 * is read from Redis on every request.
 *
 * RedisKeys.product(id)에 캐시되는 상품 정보 (REQ-F-004). 일부러 재고를 넣지 않는다. 가용 재고는 요청마다 Redis에서
 * 읽는다.
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
