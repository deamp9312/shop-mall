package com.example.shop.member;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Locale;

/** Loads members for form login. Created by the security configuration, not registered as a bean of its own. */
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
