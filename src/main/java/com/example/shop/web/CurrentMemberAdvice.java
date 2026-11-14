package com.example.shop.web;

import com.example.shop.member.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the logged-in member (or null) to every page template as "currentMember".
 *
 * 로그인한 회원(또는 null)을 모든 화면 템플릿에 "currentMember"로 노출한다.
 */
@ControllerAdvice(basePackageClasses = CurrentMemberAdvice.class)
class CurrentMemberAdvice {

    @ModelAttribute("currentMember")
    MemberPrincipal currentMember(@AuthenticationPrincipal MemberPrincipal member) {
        return member;
    }
}
