package com.example.shop.member;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository members, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a USER. The unique index is the final guard against two sign-ups racing with the same email.
     *
     * USER를 등록한다. 같은 이메일로 동시에 두 번 가입하는 경우의 최종 방어선은 유니크 인덱스다.
     */
    @Transactional
    public Long register(String email, String rawPassword, String name) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (members.existsByEmail(normalizedEmail)) {
            throw new ShopException(ErrorCode.DUPLICATE_EMAIL);
        }
        try {
            Member member = members.saveAndFlush(
                    Member.register(normalizedEmail, passwordEncoder.encode(rawPassword), name.trim()));
            log.info("member registered: memberId={}", member.getId());
            return member.getId();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ShopException(ErrorCode.DUPLICATE_EMAIL);
        }
    }
}
