package com.example.shop.member;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Locale;

/**
 * Loads members for form login. Created by the security configuration, not registered as a bean of its own.
 *
 * 폼 로그인용으로 회원을 불러온다. 별도 빈으로 등록하지 않고 보안 설정에서 생성한다.
 */
public class MemberDetailsService implements UserDetailsService {

    private final MemberRepository members;

    public MemberDetailsService(MemberRepository members) {
        this.members = members;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return members.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(MemberPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("member not found"));
    }
}
