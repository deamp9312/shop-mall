package com.example.shop.member;

import com.example.shop.common.RedisKeys;
import com.example.shop.support.SeedMembers;
import com.example.shop.support.SeededIntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REQ-F-002 (sign-up, login, Redis session, logout) and REQ-SE-001 4) (5 failures lock 5 minutes). */
class AuthenticationFlowTest extends SeededIntegrationTestSupport {

    private static final String NEW_MEMBER_EMAIL = "auth-test@shop.local";
    private static final String NEW_MEMBER_PASSWORD = "Password123!";
    private static final long SESSION_TTL_SLACK_SECONDS = 120;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from members where email = ?", NEW_MEMBER_EMAIL);
        redis.delete(RedisKeys.loginFailure(NEW_MEMBER_EMAIL));
        redis.delete(RedisKeys.loginFailure(SeedMembers.USER1_EMAIL));
    }

    @Test
    void seedAccountsLogInWithTheirSeedHashes() throws Exception {
        mvc.perform(login(SeedMembers.USER1_EMAIL, SeedMembers.USER_PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(SeedMembers.USER1_EMAIL).withRoles("USER"));
        mvc.perform(login(SeedMembers.ADMIN_EMAIL, SeedMembers.ADMIN_PASSWORD))
                .andExpect(authenticated().withUsername(SeedMembers.ADMIN_EMAIL).withRoles("ADMIN"));
    }

    @Test
    void sessionIsStoredInRedisSurvivesWithoutServerMemoryAndIsDeletedOnLogout() throws Exception {
        MvcResult login = mvc.perform(login(SeedMembers.USER1_EMAIL, SeedMembers.USER_PASSWORD))
                .andExpect(authenticated()).andReturn();
        Cookie sessionCookie = login.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();
        String sessionKey = RedisKeys.SESSION_NAMESPACE + ":sessions:"
                + new String(Base64.getDecoder().decode(sessionCookie.getValue()), StandardCharsets.UTF_8);
        assertThat(redis.hasKey(sessionKey)).isTrue();
        // TTL = 30 minutes. The slack absorbs a clock correction of the Docker VM between EXPIRE and TTL, which once
        // read 1870 s (step 4 handover); it is still far below anything a wrong timeout setting would produce.
        assertThat(redis.getExpire(sessionKey, TimeUnit.SECONDS)).isBetween(1L, 30L * 60 + SESSION_TTL_SLACK_SECONDS);

        // A new request carrying only the cookie is authenticated: the session is loaded from Redis.
        mvc.perform(get("/cart").cookie(sessionCookie)).andExpect(status().isOk());

        mvc.perform(post("/logout").cookie(sessionCookie).with(csrf())).andExpect(redirectedUrl("/"));
        assertThat(redis.hasKey(sessionKey)).isFalse();
        mvc.perform(get("/cart").cookie(sessionCookie)).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void anonymousCartRequestIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/cart")).andExpect(status().isFound()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void signUpThenLogIn() throws Exception {
        mvc.perform(signUp(NEW_MEMBER_EMAIL)).andExpect(redirectedUrl("/login?registered"));

        assertThat(jdbc.queryForObject("select role from members where email = ?", String.class, NEW_MEMBER_EMAIL))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject("select password_hash from members where email = ?", String.class,
                NEW_MEMBER_EMAIL)).startsWith("$2a$").doesNotContain(NEW_MEMBER_PASSWORD);
        mvc.perform(login(NEW_MEMBER_EMAIL, NEW_MEMBER_PASSWORD)).andExpect(authenticated().withRoles("USER"));
    }

    @Test
    void duplicateEmailIsRejectedOnTheForm() throws Exception {
        mvc.perform(signUp(SeedMembers.USER1_EMAIL))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrorCode("form", "email", "duplicate"));
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheCorrectPassword() throws Exception {
        mvc.perform(signUp(NEW_MEMBER_EMAIL));
        for (int attempt = 1; attempt <= LoginAttemptService.MAX_FAILURES; attempt++) {
            mvc.perform(login(NEW_MEMBER_EMAIL, "wrong-password")).andExpect(redirectedUrl("/login?error"));
        }

        mvc.perform(login(NEW_MEMBER_EMAIL, NEW_MEMBER_PASSWORD))
                .andExpect(redirectedUrl("/login?locked"))
                .andExpect(unauthenticated());
        assertThat(redis.getExpire(RedisKeys.loginFailure(NEW_MEMBER_EMAIL), TimeUnit.SECONDS))
                .isBetween(1L, LoginAttemptService.LOCK_DURATION.toSeconds());
    }

    @Test
    void successfulLoginBeforeTheLimitResetsTheCounter() throws Exception {
        for (int attempt = 1; attempt < LoginAttemptService.MAX_FAILURES; attempt++) {
            mvc.perform(login(SeedMembers.USER1_EMAIL, "wrong-password"));
        }

        mvc.perform(login(SeedMembers.USER1_EMAIL, SeedMembers.USER_PASSWORD)).andExpect(authenticated());
        assertThat(redis.hasKey(RedisKeys.loginFailure(SeedMembers.USER1_EMAIL))).isFalse();
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String email, String password) {
        return formLogin("/login").userParameter("email").user(email).password(password);
    }

    private static org.springframework.test.web.servlet.RequestBuilder signUp(String email) {
        return post("/signup").with(csrf())
                .param("email", email)
                .param("password", NEW_MEMBER_PASSWORD)
                .param("name", "테스트회원");
    }
}
