package com.example.shop.web;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ErrorResponse;
import com.example.shop.member.LockoutAwareAuthenticationProvider;
import com.example.shop.member.LoginAttemptService;
import com.example.shop.member.MemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * 경로·역할 규칙(REQ-SE-001 1), 잠금이 적용된 form login(REQ-F-002, REQ-SE-001 4), form과 fetch에 대한 CSRF
 * (REQ-SE-001 3)를 구성한다. JSON 엔드포인트는 {code, message}로 401/403을 응답하고, 페이지는 /login으로 리다이렉트하거나
 * 403을 보여준다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final RequestMatcher JSON_ENDPOINTS = new OrRequestMatcher(
            new AntPathRequestMatcher("/api/**"), new AntPathRequestMatcher("/mock-pg/**"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .authorizeHttpRequests(paths -> paths
                        .requestMatchers("/", "/products/**", "/login", "/signup", "/error",
                                "/css/**", "/js/**", "/images/**", "/actuator/health").permitAll()
                        .requestMatchers("/admin/**", "/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/", false)
                        .failureHandler(loginFailureHandler()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)));
        return http.build();
    }

    /** JSON 엔드포인트는 401 {code, message}를 받고, 그 외 요청은 모두 로그인 페이지로 보낸다. */
    private static AuthenticationEntryPoint entryPoint(ObjectMapper objectMapper) {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> byPath = new LinkedHashMap<>();
        byPath.put(JSON_ENDPOINTS,
                (request, response, e) -> writeError(response, objectMapper, ErrorCode.UNAUTHORIZED));
        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(byPath);
        entryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"));
        return entryPoint;
    }

    /** JSON 엔드포인트는 403 {code, message}를 받고, 페이지는 error/403 페이지를 받는다. */
    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> byPath = new LinkedHashMap<>();
        byPath.put(JSON_ENDPOINTS, (request, response, e) -> writeError(response, objectMapper, ErrorCode.FORBIDDEN));
        return new RequestMatcherDelegatingAccessDeniedHandler(byPath, new AccessDeniedHandlerImpl());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    LockoutAwareAuthenticationProvider authenticationProvider(MemberRepository members,
                                                              PasswordEncoder passwordEncoder,
                                                              LoginAttemptService loginAttempts) {
        return new LockoutAwareAuthenticationProvider(members, passwordEncoder, loginAttempts);
    }

    private static AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) -> response.sendRedirect(request.getContextPath()
                + (exception instanceof LockedException ? "/login?locked" : "/login?error"));
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(errorCode));
    }
}
