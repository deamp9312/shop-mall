package com.example.shop.product;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 상품 조회 측: 목록은 DB에서 바로, 단건은 Redis 캐시를 거쳐 조회한다(REQ-F-003, 004). */
@Service
@Transactional(readOnly = true)
public class ProductCatalog {

    public static final String CACHE_NAME = "product";

    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductCatalog(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    /**
     * <pre>
     * Redis를 직접 부르지 않는다. {@code @Cacheable} 프록시가 메서드 앞뒤에서 Redis를 확인한다.
     * [IN]  productId=3
     * [Redis] GET shop:product:3
     *           → 값 있음 (cache hit) : 메서드 본문(DB 조회)을 실행하지 않고 JSON을 ProductInfo로 바꿔 반환
     *           → (nil)  (cache miss): DB 조회 후 SET shop:product:3 '{...}' PX 600000 (TTL 10분)
     *         저장되는 값: {"id":3,"categoryId":1,"categoryName":"의류","name":"반팔 티셔츠","description":"...",
     *                      "price":8900,"imageUrl":"/images/3.jpg","saleStatus":"ON_SALE"}
     * [OUT] ProductInfo(id=3, name="반팔 티셔츠", price=8900, ...)
     * </pre>
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "#productId")
    public ProductInfo getProduct(long productId) {
        return products.findWithCategoryById(productId)
                .map(ProductInfo::from)
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /** @param pageIndex 0부터 시작하는 페이지 인덱스 */
    public Page<ProductInfo> search(ProductSearchCondition condition, int pageIndex) {
        PageRequest page = PageRequest.of(pageIndex, ProductSearchCondition.PAGE_SIZE, condition.sort().sort());
        return products.searchOnSale(condition.categoryId(), condition.likePattern(), page).map(ProductInfo::from);
    }

    public List<Category> categories() {
        return categories.findAll();
    }
}
