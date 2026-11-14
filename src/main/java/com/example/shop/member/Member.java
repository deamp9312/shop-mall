package com.example.shop.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Member account; the password is stored only as a BCrypt hash (REQ-F-002, REQ-SE-001).
 *
 * 회원 계정. 비밀번호는 BCrypt 해시로만 저장한다 (REQ-F-002, REQ-SE-001).
 */
@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Member() {
    }

    /**
     * Self sign-up always creates a USER; ADMIN exists only as seed data (REQ-F-002 3).
     *
     * 직접 가입하면 항상 USER가 만들어진다. ADMIN은 시드 데이터로만 존재한다 (REQ-F-002 3).
     */
    public static Member register(String email, String passwordHash, String name) {
        Member member = new Member();
        member.email = email;
        member.passwordHash = passwordHash;
        member.name = name;
        member.role = MemberRole.USER;
        member.createdAt = LocalDateTime.now();
        return member;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public MemberRole getRole() {
        return role;
    }
}
