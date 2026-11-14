package com.example.shop.product;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read side of products: listing straight from the DB, single product through the Redis cache (REQ-F-003, 004).
 *
 * 상품 조회 쪽: 목록은 DB에서 바로, 단건은 Redis 캐시를 거쳐 읽는다 (REQ-F-003, 004).
 */
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

    @Cacheable(cacheNames = CACHE_NAME, key = "#productId")
    public ProductInfo getProduct(long productId) {
        return products.findWithCategoryById(productId)
                .map(ProductInfo::from)
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * @param pageIndex zero-based page index
     *
     * pageIndex: 0부터 시작하는 페이지 번호
     */
    public Page<ProductInfo> search(ProductSearchCondition condition, int pageIndex) {
        PageRequest page = PageRequest.of(pageIndex, ProductSearchCondition.PAGE_SIZE, condition.sort().sort());
        return products.searchOnSale(condition.categoryId(), condition.likePattern(), page).map(ProductInfo::from);
    }

    public List<Category> categories() {
        return categories.findAll();
    }
}
