package com.example.shop.member;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * Logged-in member kept in the Redis session (JDK-serialized by Spring Session). The password hash is erased after
 * authentication so it never reaches Redis.
 *
 * Redis 세션에 보관되는 로그인 회원 (Spring Session이 JDK 직렬화). 비밀번호 해시는 인증 후 지우므로 Redis에 들어가지
 * 않는다.
 */
public class MemberPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long memberId;
    private final String email;
    private final String name;
    private final MemberRole role;
    private String passwordHash;

    public MemberPrincipal(Long memberId, String email, String name, MemberRole role, String passwordHash) {
        this.memberId = memberId;
        this.email = email;
        this.name = name;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    static MemberPrincipal from(Member member) {
        return new MemberPrincipal(member.getId(), member.getEmail(), member.getName(), member.getRole(),
                member.getPasswordHash());
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getName() {
        return name;
    }

    public boolean isAdmin() {
        return role == MemberRole.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
