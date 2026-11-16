package com.example.shop.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    /** 관리자 이벤트 화면용으로 최신순 조회한다 (REQ-F-014 2). */
    List<DeadLetterEvent> findTop50ByOrderByIdDesc();
}
