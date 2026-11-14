package com.example.shop.web;

import com.example.shop.support.SeedMembers;
import com.example.shop.support.SeededIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-SE-001 1) path x role matrix, 3) CSRF, and REQ-F-013 2) USER gets 403 on /admin.
 *
 * REQ-SE-001 1) 경로 x 역할 매트릭스, 3) CSRF, 그리고 REQ-F-013 2) USER는 /admin에서 403을 받는다.
 */
class AuthorizationTest extends SeededIntegrationTestSupport {

    @Test
    void anonymousCanBrowseProductsOnly() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/products/1")).andExpect(status().isOk());
        mvc.perform(get("/cart")).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin/products")).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void anonymousApiCallGets401Json() throws Exception {
        mvc.perform(get("/api/cart").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void userCanUseCartButNotAdmin() throws Exception {
        mvc.perform(get("/cart").with(user(SeedMembers.user1()))).andExpect(status().isOk());
        mvc.perform(get("/api/cart").with(user(SeedMembers.user1()))).andExpect(status().isOk());
        mvc.perform(get("/admin/products").with(user(SeedMembers.user1()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stock/resync").with(user(SeedMembers.user1())).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanOpenAdminPages() throws Exception {
        mvc.perform(get("/admin/products").with(user(SeedMembers.admin()))).andExpect(status().isOk());
        mvc.perform(get("/admin/products/new").with(user(SeedMembers.admin()))).andExpect(status().isOk());
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/cart/items").with(user(SeedMembers.user1()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"qty\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/signup").param("email", "x@shop.local").param("password", "Password123!")
                .param("name", "x")).andExpect(status().isForbidden());
    }
}
