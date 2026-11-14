package com.example.shop.product;

import com.example.shop.support.SeededIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-F-003: listing results equal the same filter run directly on the DB; sold-out comes from Redis.
 *
 * REQ-F-003: 목록 결과는 같은 필터를 DB에 직접 돌린 결과와 같다. 품절 여부는 Redis에서 온다.
 */
class ProductListingTest extends SeededIntegrationTestSupport {

    private static final long SOLD_OUT_PRODUCT_ID = 10L;
    private static final long KITCHEN_CATEGORY_ID = 2L;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void firstAndSecondPageHoldTwelveThenTheRestInLatestOrder() throws Exception {
        List<Long> expected = jdbc.queryForList("select id from products where sale_status = 'ON_SALE'"
                + " order by created_at desc, id desc", Long.class);

        List<Long> firstPage = listedIds("/");
        List<Long> secondPage = listedIds("/?page=2");

        assertThat(firstPage).hasSize(ProductSearchCondition.PAGE_SIZE)
                .isEqualTo(expected.subList(0, ProductSearchCondition.PAGE_SIZE));
        assertThat(secondPage).isEqualTo(expected.subList(ProductSearchCondition.PAGE_SIZE, expected.size()));
    }

    @Test
    void categoryFilterWithPriceSortMatchesDatabase() throws Exception {
        List<Long> expected = jdbc.queryForList("select id from products where sale_status = 'ON_SALE'"
                + " and category_id = ? order by price asc, id asc", Long.class, KITCHEN_CATEGORY_ID);

        assertThat(listedIds("/?category=" + KITCHEN_CATEGORY_ID + "&sort=PRICE_ASC")).isEqualTo(expected);
    }

    @Test
    void keywordSearchWithDescendingPriceMatchesDatabase() throws Exception {
        List<Long> expected = jdbc.queryForList("select id from products where sale_status = 'ON_SALE'"
                + " and name like '%노트%' order by price desc, id asc", Long.class);

        assertThat(expected).isNotEmpty();
        assertThat(listedIds("/?q=노트&sort=PRICE_DESC")).isEqualTo(expected);
    }

    @Test
    void likeWildcardsInKeywordAreSearchedLiterally() throws Exception {
        assertThat(listedIds("/?q=%25")).isEmpty();
        assertThat(listedIds("/?q=_")).isEmpty();
    }

    @Test
    void soldOutProductIsMarkedAndCannotBeOrdered() throws Exception {
        String kitchenPage = mvc.perform(get("/?category=" + KITCHEN_CATEGORY_ID))
                .andReturn().getResponse().getContentAsString();
        assertThat(kitchenPage.split("품절", -1)).hasSize(2);

        String detail = mvc.perform(get("/products/" + SOLD_OUT_PRODUCT_ID))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("품절").doesNotContain("data-cart-add");
    }

    @SuppressWarnings("unchecked")
    private List<Long> listedIds(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        Page<ProductInfo> page = (Page<ProductInfo>) result.getModelAndView().getModel().get("products");
        return page.getContent().stream().map(ProductInfo::id).toList();
    }
}
