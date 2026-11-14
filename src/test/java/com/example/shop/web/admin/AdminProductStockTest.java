package com.example.shop.web.admin;

import com.example.shop.common.RedisKeys;
import com.example.shop.product.ProductInfo;
import com.example.shop.stock.StockReservation;
import com.example.shop.stock.StockReservationRepository;
import com.example.shop.support.SeedMembers;
import com.example.shop.support.SeededIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-F-013 (admin product and stock management) and REQ-IF-001 6) resync API.
 *
 * REQ-F-013 (관리자 상품·재고 관리)와 REQ-IF-001 6) 재동기화 API.
 */
class AdminProductStockTest extends SeededIntegrationTestSupport {

    private static final long ADJUSTED_PRODUCT = 12L;     // seed stock 100
    // 시드 재고 100
    private static final long RESERVED_PRODUCT = 13L;     // seed stock 55
    // 시드 재고 55
    private static final long RESYNC_PRODUCT = 14L;       // seed stock 65
    // 시드 재고 65
    private static final long RESERVATION_ORDER_ID = 9_999L;
    private static final String NEW_PRODUCT_NAME = "관리자 등록 테스트 상품";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockReservationRepository reservations;

    @AfterEach
    void restoreSeed() {
        jdbc.update("update products set stock = 100 where id = ?", ADJUSTED_PRODUCT);
        jdbc.update("delete from stock_reservations where order_id = ?", RESERVATION_ORDER_ID);
        List<Long> created = jdbc.queryForList("select id from products where name = ?", Long.class,
                NEW_PRODUCT_NAME);
        for (Long id : created) {
            jdbc.update("delete from products where id = ?", id);
            redis.delete(List.of(RedisKeys.stock(id), RedisKeys.product(id)));
        }
        redis.delete(RedisKeys.lock("stock-resync"));
        jdbc.queryForList("select id, stock from products").forEach(row -> redis.opsForValue().set(
                RedisKeys.stock(((Number) row.get("id")).longValue()), String.valueOf(row.get("stock"))));
    }

    @Test
    void stockAdjustmentIsVisibleOnTheDetailImmediately() throws Exception {
        adjustStock(ADJUSTED_PRODUCT, 130).andExpect(flash().attributeExists("notice"));

        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, ADJUSTED_PRODUCT))
                .isEqualTo(130);
        mvc.perform(get("/products/" + ADJUSTED_PRODUCT)).andExpect(model().attribute("available", 130L));
    }

    @Test
    void adjustmentMovesRedisByTheDeltaSoReservationsStayCounted() throws Exception {
        reservations.save(new StockReservation(RESERVATION_ORDER_ID, RESERVED_PRODUCT, 30));
        redis.opsForValue().set(RedisKeys.stock(RESERVED_PRODUCT), "25"); // 55 - 30 reserved
        // 55 - 선점 30

        adjustStock(RESERVED_PRODUCT, 60);
        assertThat(redis.opsForValue().get(RedisKeys.stock(RESERVED_PRODUCT))).isEqualTo("30");

        adjustStock(RESERVED_PRODUCT, 20).andExpect(flash().attributeExists("error"));
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, RESERVED_PRODUCT))
                .isEqualTo(60);
        jdbc.update("update products set stock = 55 where id = ?", RESERVED_PRODUCT);
    }

    @Test
    void resyncRebuildsRedisFromDatabaseMinusReserved() throws Exception {
        redis.opsForValue().set(RedisKeys.stock(RESYNC_PRODUCT), "999");
        reservations.save(new StockReservation(RESERVATION_ORDER_ID, RESYNC_PRODUCT, 5));
        int productCount = jdbc.queryForObject("select count(*) from products", Integer.class);

        mvc.perform(post("/api/admin/stock/resync").with(user(SeedMembers.admin())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.synchronizedProducts").value(productCount));

        assertThat(redis.opsForValue().get(RedisKeys.stock(RESYNC_PRODUCT))).isEqualTo("60");
    }

    @Test
    void resyncIsRefusedWhileTheLockIsHeld() throws Exception {
        redis.opsForValue().set(RedisKeys.lock("stock-resync"), "someone-else");

        mvc.perform(post("/api/admin/stock/resync").with(user(SeedMembers.admin())).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_SYNC_IN_PROGRESS"));
        assertThat(redis.opsForValue().get(RedisKeys.lock("stock-resync"))).isEqualTo("someone-else");
    }

    @Test
    void registeredProductStartsWithZeroStockAndStopSaleHidesIt() throws Exception {
        mvc.perform(post("/admin/products").with(user(SeedMembers.admin())).with(csrf())
                        .param("categoryId", "3").param("name", NEW_PRODUCT_NAME).param("description", "설명")
                        .param("price", "5000").param("imageFileName", "placeholder.svg"))
                .andExpect(status().is3xxRedirection());
        Long productId = jdbc.queryForObject("select id from products where name = ?", Long.class, NEW_PRODUCT_NAME);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId))
                .isZero();
        mvc.perform(get("/products/" + productId)).andExpect(model().attribute("available", 0L));
        assertThat(listedNames()).contains(NEW_PRODUCT_NAME);

        mvc.perform(post("/admin/products/" + productId + "/stop-sale").with(user(SeedMembers.admin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(listedNames()).doesNotContain(NEW_PRODUCT_NAME);
        ProductInfo detail = (ProductInfo) mvc.perform(get("/products/" + productId)).andReturn()
                .getModelAndView().getModel().get("product");
        assertThat(detail.onSale()).isFalse();
    }

    @Test
    void unknownSampleImageIsAFormError() throws Exception {
        mvc.perform(post("/admin/products").with(user(SeedMembers.admin())).with(csrf())
                        .param("categoryId", "3").param("name", NEW_PRODUCT_NAME).param("description", "설명")
                        .param("price", "5000").param("imageFileName", "../../secret.txt"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "imageFileName"));
    }

    private org.springframework.test.web.servlet.ResultActions adjustStock(long productId, int newStock)
            throws Exception {
        return mvc.perform(post("/admin/products/" + productId + "/stock").with(user(SeedMembers.admin()))
                .with(csrf()).param("newStock", String.valueOf(newStock)));
    }

    @SuppressWarnings("unchecked")
    private List<String> listedNames() throws Exception {
        var page = (org.springframework.data.domain.Page<ProductInfo>) mvc.perform(get("/?q=관리자 등록"))
                .andReturn().getModelAndView().getModel().get("products");
        return page.getContent().stream().map(ProductInfo::name).toList();
    }
}
