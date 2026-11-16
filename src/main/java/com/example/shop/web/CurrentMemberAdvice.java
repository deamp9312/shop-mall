package com.example.shop.web;

import com.example.shop.member.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 로그인한 회원(없으면 null)을 모든 페이지 템플릿에 "currentMember"로 노출한다. */
@ControllerAdvice(basePackageClasses = CurrentMemberAdvice.class)
class CurrentMemberAdvice {

    @ModelAttribute("currentMember")
    MemberPrincipal currentMember(@AuthenticationPrincipal MemberPrincipal member) {
        return member;
    }
}
