package com.example.shop.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByOrderIdOrderByIdAsc(Long orderId);
}
