package com.example.shop.support;

import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;

/**
 * Principals of the seed accounts (db/seed), for MockMvc's user(...) post-processor.
 *
 * 시드 계정(db/seed)의 principal. MockMvc의 user(...) post-processor용.
 */
public final class SeedMembers {

    public static final String USER1_EMAIL = "user1@shop.local";
    public static final String USER_PASSWORD = "User1234!";
    public static final String ADMIN_EMAIL = "admin@shop.local";
    public static final String ADMIN_PASSWORD = "Admin1234!";

    private SeedMembers() {
    }

    public static MemberPrincipal user1() {
        return new MemberPrincipal(1L, USER1_EMAIL, "사용자1", MemberRole.USER, null);
    }

    public static MemberPrincipal user2() {
        return new MemberPrincipal(2L, "user2@shop.local", "사용자2", MemberRole.USER, null);
    }

    public static MemberPrincipal admin() {
        return new MemberPrincipal(3L, ADMIN_EMAIL, "관리자", MemberRole.ADMIN, null);
    }
}
