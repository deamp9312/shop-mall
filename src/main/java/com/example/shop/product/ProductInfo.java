package com.example.shop.product;

/**
 * RedisKeys.product(id)에 캐시되는 상품 정보(REQ-F-004). 의도적으로 재고를 두지 않는다. 가용 재고는 요청마다
 * Redis에서 읽는다.
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
