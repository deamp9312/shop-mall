package com.example.shop.member;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Locale;

/** form login용으로 회원을 조회한다. 보안 설정에서 생성하며, 별도 빈으로 등록하지 않는다. */
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
