package com.example.shop.cart;

import com.example.shop.common.RedisKeys;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.support.SeedMembers;
import com.example.shop.support.SeededIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-F-005 and REQ-IF-001 1): Redis Hash cart through the REST API.
 *
 * REQ-F-005와 REQ-IF-001 1): REST API로 다루는 Redis Hash 장바구니.
 */
class CartApiTest extends SeededIntegrationTestSupport {

    private static final long TOWEL = 1L;      // 12,900 won, stock 80
    // 12,900원, 재고 80
    private static final long SOAP = 3L;       //  8,900 won, stock 100
    // 8,900원, 재고 100
    private static final long SOLD_OUT = 10L;  // stock 0
    // 재고 0
    private static final long STOPPED_PRODUCT_ID = 9_101L;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redis.delete(RedisKeys.cart(1L));
        redis.delete(RedisKeys.cart(2L));
        jdbc.update("delete from products where id = ?", STOPPED_PRODUCT_ID);
    }

    @Test
    void addedItemsInRedisMatchTheApiResponse() throws Exception {
        add(SeedMembers.user1(), TOWEL, 2).andExpect(status().isOk());
        add(SeedMembers.user1(), SOAP, 3).andExpect(status().isOk());

        Map<Object, Object> hash = redis.opsForHash().entries(RedisKeys.cart(1L));
        assertThat(hash).containsExactlyInAnyOrderEntriesOf(Map.of("1", "2", "3", "3"));
        mvc.perform(get("/api/cart").with(user(SeedMembers.user1())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].productId").value(TOWEL))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].subtotal").value(2 * 12_900))
                .andExpect(jsonPath("$.lines[1].productId").value(SOAP))
                .andExpect(jsonPath("$.lines[1].quantity").value(3))
                .andExpect(jsonPath("$.totalAmount").value(2 * 12_900 + 3 * 8_900));
    }

    @Test
    void addingTheSameProductSumsQuantityAndRenewsSevenDayTtl() throws Exception {
        add(SeedMembers.user1(), TOWEL, 2);
        redis.expire(RedisKeys.cart(1L), 60, TimeUnit.SECONDS);

        add(SeedMembers.user1(), TOWEL, 3).andExpect(jsonPath("$.lines[0].quantity").value(5));

        assertThat(redis.getExpire(RedisKeys.cart(1L), TimeUnit.SECONDS))
                .isGreaterThan(CartService.CART_TTL.minusMinutes(1).toSeconds());
    }

    @Test
    void quantityElevenIsBadRequestWithCommonErrorBody() throws Exception {
        add(SeedMembers.user1(), TOWEL, 11)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(redis.hasKey(RedisKeys.cart(1L))).isFalse();
    }

    @Test
    void sumOverTenIsRejectedAndKeepsTheOldQuantity() throws Exception {
        add(SeedMembers.user1(), TOWEL, 6);

        add(SeedMembers.user1(), TOWEL, 5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CART_QUANTITY_LIMIT"));
        assertThat(redis.opsForHash().get(RedisKeys.cart(1L), "1")).isEqualTo("6");
    }

    @Test
    void thirtyFirstProductKindIsRejected() throws Exception {
        Map<String, String> thirtyKinds = new HashMap<>();
        for (int i = 0; i < CartService.MAX_PRODUCT_KINDS; i++) {
            thirtyKinds.put(String.valueOf(5_000 + i), "1");
        }
        redis.opsForHash().putAll(RedisKeys.cart(1L), thirtyKinds);

        add(SeedMembers.user1(), TOWEL, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CART_ITEM_LIMIT"));
        assertThat(redis.opsForHash().size(RedisKeys.cart(1L))).isEqualTo(CartService.MAX_PRODUCT_KINDS);
    }

    @Test
    void changeRemoveAndClear() throws Exception {
        add(SeedMembers.user1(), TOWEL, 2);
        add(SeedMembers.user1(), SOAP, 1);

        mvc.perform(patch("/api/cart/items/" + TOWEL).with(user(SeedMembers.user1())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":4}"))
                .andExpect(jsonPath("$.lines[0].quantity").value(4));
        mvc.perform(patch("/api/cart/items/" + SOLD_OUT).with(user(SeedMembers.user1())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
        mvc.perform(patch("/api/cart/items/" + TOWEL).with(user(SeedMembers.user1())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":11}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/cart/items/" + SOAP).with(user(SeedMembers.user1())).with(csrf()))
                .andExpect(jsonPath("$.lines.length()").value(1));
        assertThat(redis.opsForHash().entries(RedisKeys.cart(1L))).containsOnlyKeys("1");

        mvc.perform(delete("/api/cart").with(user(SeedMembers.user1())).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(redis.hasKey(RedisKeys.cart(1L))).isFalse();
    }

    @Test
    void soldOutLineIsShownButExcludedFromTotal() throws Exception {
        add(SeedMembers.user1(), TOWEL, 1);
        add(SeedMembers.user1(), SOLD_OUT, 1).andExpect(status().isOk());

        mvc.perform(get("/api/cart").with(user(SeedMembers.user1())))
                .andExpect(jsonPath("$.lines[1].productId").value(SOLD_OUT))
                .andExpect(jsonPath("$.lines[1].soldOut").value(true))
                .andExpect(jsonPath("$.lines[1].orderable").value(false))
                .andExpect(jsonPath("$.totalAmount").value(12_900));
        String page = mvc.perform(get("/cart").with(user(SeedMembers.user1()))).andReturn().getResponse()
                .getContentAsString();
        assertThat(page).contains("품절 - 주문 제외");
    }

    @Test
    void stoppedProductCannotBeAdded() throws Exception {
        jdbc.update("insert into products (id, category_id, name, description, price, stock, image_url, sale_status,"
                + " created_at, updated_at) values (?, 1, 'stopped', 'd', 1000, 5, '/images/products/placeholder.svg',"
                + " 'STOPPED', now(6), now(6))", STOPPED_PRODUCT_ID);
        redis.delete(RedisKeys.product(STOPPED_PRODUCT_ID));

        add(SeedMembers.user1(), STOPPED_PRODUCT_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_ON_SALE"));
        redis.delete(RedisKeys.product(STOPPED_PRODUCT_ID));
    }

    @Test
    void unknownProductIs404() throws Exception {
        add(SeedMembers.user1(), 999_999L, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void eachMemberSeesOnlyTheirOwnCart() throws Exception {
        add(SeedMembers.user1(), TOWEL, 2);

        mvc.perform(get("/api/cart").with(user(SeedMembers.user2())))
                .andExpect(jsonPath("$.lines.length()").value(0));
    }

    private ResultActions add(MemberPrincipal member, long productId, int qty) throws Exception {
        return mvc.perform(post("/api/cart/items").with(user(member)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId + ",\"qty\":" + qty + "}"));
    }
}
