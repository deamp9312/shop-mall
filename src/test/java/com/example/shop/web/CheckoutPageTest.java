package com.example.shop.web;

import com.example.shop.common.RedisKeys;
import com.example.shop.support.SeedMembers;
import com.example.shop.support.SeededIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;

/** REQ-F-006 1) order form, REQ-SE-002 1) a new idempotency key per visit; order buttons are enabled. */
class CheckoutPageTest extends SeededIntegrationTestSupport {

    private static final Pattern KEY = Pattern.compile("data-idempotency-key=\"([0-9a-f-]{36})\"");

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        redis.delete(RedisKeys.cart(1L));
    }

    @Test
    void buyNowFormShowsServerPriceAndANewKeyPerVisit() throws Exception {
        String first = page("/orders/checkout?productId=1&qty=2");
        String second = page("/orders/checkout?productId=1&qty=2");

        assertThat(first).contains("25,800원", "data-place-order", "data-from-cart=\"false\"");
        assertThat(keyOf(first)).isNotEqualTo(keyOf(second));
    }

    @Test
    void cartFormContainsOnlyOrderableLines() throws Exception {
        redis.opsForHash().putAll(RedisKeys.cart(1L), Map.of("1", "1", "10", "1"));

        String page = page("/orders/checkout");

        assertThat(page).contains("data-product-id=\"1\"", "data-from-cart=\"true\"").doesNotContain("data-product-id=\"10\"");
        assertThat(page("/cart")).contains("href=\"/orders/checkout\"").doesNotContain("다음 단계");
        assertThat(page("/products/1")).contains("data-buy-now");
    }

    @Test
    void anonymousIsSentToLogin() throws Exception {
        mvc.perform(get("/orders/checkout")).andExpect(redirectedUrlPattern("**/login"));
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url).with(user(SeedMembers.user1()))).andReturn().getResponse().getContentAsString();
    }

    private static String keyOf(String page) {
        Matcher matcher = KEY.matcher(page);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
