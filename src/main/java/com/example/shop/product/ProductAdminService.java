package com.example.shop.product;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Admin product registration, edit and stop-sale (REQ-F-013). Every change evicts RedisKeys.product(id). */
@Service
public class ProductAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdminService.class);

    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductAdminService(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<ProductInfo> findAll() {
        return products.findAllWithCategory().stream().map(ProductInfo::from).toList();
    }

    /** Reads the DB directly (not the cache) so the edit form always shows the stored values. */
    @Transactional(readOnly = true)
    public ProductInfo get(long productId) {
        return products.findWithCategoryById(productId)
                .map(ProductInfo::from)
                .orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Transactional
    public Long create(long categoryId, ProductDetails details) {
        Product product = products.save(Product.create(category(categoryId), details));
        log.info("product created: productId={}", product.getId());
        return product.getId();
    }

    @Transactional
    @CacheEvict(cacheNames = ProductCatalog.CACHE_NAME, key = "#productId")
    public void update(long productId, long categoryId, ProductDetails details) {
        product(productId).update(category(categoryId), details);
        log.info("product updated: productId={}", productId);
    }

    @Transactional
    @CacheEvict(cacheNames = ProductCatalog.CACHE_NAME, key = "#productId")
    public void stopSale(long productId) {
        product(productId).stopSale();
        log.info("product sale stopped: productId={}", productId);
    }

    private Product product(long productId) {
        return products.findById(productId).orElseThrow(() -> new ShopException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private Category category(long categoryId) {
        return categories.findById(categoryId).orElseThrow(() -> new ShopException(ErrorCode.INVALID_INPUT));
    }
}
