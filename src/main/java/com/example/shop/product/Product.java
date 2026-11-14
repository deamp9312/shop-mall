package com.example.shop.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Product. {@code stock} is the source of truth for stock and is changed only by the stock module; the available
 * count lives in Redis (REQ-F-007). Product information is edited by the admin (REQ-F-013).
 *
 * 상품. {@code stock}은 재고의 정본이며 재고 모듈만 바꾼다. 가용 수량은 Redis에 있다 (REQ-F-007). 상품 정보는 관리자가
 * 수정한다 (REQ-F-013).
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stock;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sale_status", nullable = false, length = 20)
    private ProductSaleStatus saleStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Product() {
    }

    /**
     * New products start with stock 0; stock is set afterwards through the stock adjustment (REQ-F-013 2).
     *
     * 새 상품은 재고 0으로 시작한다. 재고는 이후 재고 조정으로 설정한다 (REQ-F-013 2).
     */
    public static Product create(Category category, ProductDetails details) {
        Product product = new Product();
        product.stock = 0;
        product.saleStatus = ProductSaleStatus.ON_SALE;
        product.createdAt = LocalDateTime.now();
        product.apply(category, details);
        return product;
    }

    public void update(Category category, ProductDetails details) {
        apply(category, details);
    }

    public void stopSale() {
        saleStatus = ProductSaleStatus.STOPPED;
        updatedAt = LocalDateTime.now();
    }

    private void apply(Category category, ProductDetails details) {
        this.category = category;
        this.name = details.name();
        this.description = details.description();
        this.price = details.price();
        this.imageUrl = details.imageUrl();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getPrice() {
        return price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public ProductSaleStatus getSaleStatus() {
        return saleStatus;
    }

    public int getStock() {
        return stock;
    }
}
