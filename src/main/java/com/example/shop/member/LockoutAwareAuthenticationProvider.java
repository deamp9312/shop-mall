package com.example.shop.member;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Form login with the 5-failure lock (REQ-SE-001 4). The lock is checked before the member lookup, so a locked
 * unknown email and a locked real account answer the same way (no account enumeration).
 *
 * 5회 실패 잠금이 있는 폼 로그인 (REQ-SE-001 4). 회원 조회 전에 잠금을 확인하므로, 잠긴 미가입 이메일과 잠긴 실제
 * 계정이 같은 응답을 받는다 (계정 존재 여부 노출 없음).
 */
public class LockoutAwareAuthenticationProvider extends DaoAuthenticationProvider {

    private final LoginAttemptService loginAttempts;

    public LockoutAwareAuthenticationProvider(MemberRepository members, PasswordEncoder passwordEncoder,
                                              LoginAttemptService loginAttempts) {
        super(passwordEncoder);
        setUserDetailsService(new MemberDetailsService(members));
        this.loginAttempts = loginAttempts;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String email = authentication.getName().trim();
        if (loginAttempts.isLocked(email)) {
            throw new LockedException("login locked");
        }
        try {
            Authentication authenticated = super.authenticate(authentication);
            loginAttempts.reset(email);
            return authenticated;
        } catch (BadCredentialsException badCredentials) {
            loginAttempts.recordFailure(email);
            throw badCredentials;
        }
    }
}
