package com.example.shop.product;

import com.example.shop.common.RedisKeys;
import com.example.shop.support.SeedMembers;
import com.example.shop.support.SeededIntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** REQ-F-004: product information is cached as JSON for 10 minutes; available stock is never cached. */
class ProductDetailCacheTest extends SeededIntegrationTestSupport {

    private static final long PRODUCT_ID = 3L;
    private static final long EDITED_PRODUCT_ID = 4L;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private String originalStock;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        redis.delete(RedisKeys.product(PRODUCT_ID));
        originalStock = redis.opsForValue().get(RedisKeys.stock(PRODUCT_ID));
    }

    @AfterEach
    void restore() {
        redis.opsForValue().set(RedisKeys.stock(PRODUCT_ID), originalStock);
    }

    @Test
    void secondReadIsServedFromCacheWithoutSql() throws Exception {
        long firstReadStatements = statementsFor(() -> mvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk()));
        long secondReadStatements = statementsFor(() -> mvc.perform(get("/products/" + PRODUCT_ID))
                .andExpect(status().isOk()));

        assertThat(firstReadStatements).isEqualTo(1);
        assertThat(secondReadStatements).isZero();
    }

    @Test
    void cacheEntryIsPlainJsonWithTenMinuteTtlAndNoStock() throws Exception {
        mvc.perform(get("/products/" + PRODUCT_ID));

        String cached = redis.opsForValue().get(RedisKeys.product(PRODUCT_ID));
        assertThat(cached).startsWith("{").contains("\"name\"").doesNotContain("@class").doesNotContain("stock");
        assertThat(redis.getExpire(RedisKeys.product(PRODUCT_ID), TimeUnit.SECONDS)).isBetween(590L, 600L);
    }

    @Test
    void expiredEntryIsReloadedWithOneQuery() throws Exception {
        mvc.perform(get("/products/" + PRODUCT_ID));
        redis.delete(RedisKeys.product(PRODUCT_ID)); // same effect as the TTL running out

        long statements = statementsFor(() -> mvc.perform(get("/products/" + PRODUCT_ID)));

        assertThat(statements).isEqualTo(1);
        assertThat(redis.hasKey(RedisKeys.product(PRODUCT_ID))).isTrue();
    }

    @Test
    void availableStockIsReadFromRedisOnEveryRequest() throws Exception {
        mvc.perform(get("/products/" + PRODUCT_ID)).andExpect(model().attribute("available", 
                Long.parseLong(originalStock)));

        redis.opsForValue().set(RedisKeys.stock(PRODUCT_ID), "77");

        mvc.perform(get("/products/" + PRODUCT_ID)).andExpect(model().attribute("available", 77L));
    }

    @Test
    void unknownProductRendersNotFoundPage() throws Exception {
        mvc.perform(get("/products/999999")).andExpect(status().isNotFound()).andExpect(view().name("error/404"));
    }

    @Test
    void adminEditEvictsTheCacheSoTheDetailShowsTheNewName() throws Exception {
        ProductInfo original = (ProductInfo) mvc.perform(get("/products/" + EDITED_PRODUCT_ID)).andReturn()
                .getModelAndView().getModel().get("product");
        try {
            editName(original, "수정된 발매트");

            assertThat(redis.hasKey(RedisKeys.product(EDITED_PRODUCT_ID))).isFalse();
            String page = mvc.perform(get("/products/" + EDITED_PRODUCT_ID)).andReturn().getResponse()
                    .getContentAsString();
            assertThat(page).contains("수정된 발매트");
        } finally {
            editName(original, original.name());
        }
    }

    private void editName(ProductInfo product, String name) throws Exception {
        mvc.perform(post("/admin/products/" + product.id()).with(user(SeedMembers.admin())).with(csrf())
                        .param("categoryId", String.valueOf(product.categoryId()))
                        .param("name", name)
                        .param("description", product.description())
                        .param("price", String.valueOf(product.price()))
                        .param("imageFileName", "placeholder.svg"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Product rows loaded from the DB during the action. Counted per entity rather than with the global statement
     * count, because the outbox relay (REQ-DA-002) polls the DB in the background every 500 ms.
     */
    private long statementsFor(ThrowingAction action) throws Exception {
        statistics.clear();
        action.run();
        return statistics.getEntityStatistics(Product.class.getName()).getLoadCount();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
