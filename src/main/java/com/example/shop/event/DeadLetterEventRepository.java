package com.example.shop.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    /**
     * Newest first, for the admin event screen (REQ-F-014 2).
     *
     * 최신순. 관리자 이벤트 화면용 (REQ-F-014 2).
     */
    List<DeadLetterEvent> findTop50ByOrderByIdDesc();
}
