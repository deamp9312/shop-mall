package com.example.shop.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Escape character used by {@link ProductSearchCondition#likePattern()}.
     *
     * {@link ProductSearchCondition#likePattern()}이 쓰는 이스케이프 문자.
     */
    char LIKE_ESCAPE = '!';

    /**
     * On-sale products filtered by category and a name substring (REQ-F-003, DB LIKE search).
     *
     * 카테고리와 이름 부분 문자열로 거른 판매 중 상품 (REQ-F-003, DB LIKE 검색).
     */
    @EntityGraph(attributePaths = "category")
    @Query(value = """
            select p from Product p
            where p.saleStatus = com.example.shop.product.ProductSaleStatus.ON_SALE
              and (:categoryId is null or p.category.id = :categoryId)
              and (:namePattern is null or p.name like :namePattern escape '!')
            """,
            countQuery = """
            select count(p) from Product p
            where p.saleStatus = com.example.shop.product.ProductSaleStatus.ON_SALE
              and (:categoryId is null or p.category.id = :categoryId)
              and (:namePattern is null or p.name like :namePattern escape '!')
            """)
    Page<Product> searchOnSale(@Param("categoryId") Long categoryId, @Param("namePattern") String namePattern,
                               Pageable pageable);

    @EntityGraph(attributePaths = "category")
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findWithCategoryById(@Param("id") Long id);

    @EntityGraph(attributePaths = "category")
    @Query("select p from Product p order by p.id")
    List<Product> findAllWithCategory();
}
