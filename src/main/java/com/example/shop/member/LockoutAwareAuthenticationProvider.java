package com.example.shop.member;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 5회 실패 잠금이 적용된 form login(REQ-SE-001 4). 잠금은 회원 조회보다 먼저 확인하므로, 잠긴 미가입 이메일과 잠긴
 * 실제 계정이 동일하게 응답한다(계정 열거 방지).
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
