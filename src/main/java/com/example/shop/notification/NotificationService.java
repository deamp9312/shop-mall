package com.example.shop.notification;

import com.example.shop.order.OrderQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stores payment result notices (REQ-F-009 4) and lists them for the order detail page. Payment events carry no member,
 * so the recipient is read from the order module (a read, not a state change across modules).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository notifications;
    private final OrderQueryService orders;

    NotificationService(NotificationLogRepository notifications, OrderQueryService orders) {
        this.notifications = notifications;
        this.orders = orders;
    }

    /** Joins the idempotent consumer transaction, so a redelivered event stores no second notice. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long orderId, NotificationType type, String eventId) {
        notifications.save(new NotificationLog(orderId, orders.memberIdOf(orderId), type, eventId));
        log.info("notification stored: type={}", type);
    }

    @Transactional(readOnly = true)
    public List<NotificationLog> findByOrder(long orderId) {
        return notifications.findByOrderIdOrderByIdAsc(orderId);
    }
}
