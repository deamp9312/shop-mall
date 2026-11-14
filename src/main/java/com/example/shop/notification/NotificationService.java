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
 *
 * 결제 결과 알림을 저장하고 (REQ-F-009 4) 주문 상세 화면용으로 조회한다. 결제 이벤트에는 회원 정보가 없으므로 수신자는
 * 주문 모듈에서 읽어 온다 (모듈 간 상태 변경이 아닌 읽기).
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

    /**
     * Joins the idempotent consumer transaction, so a redelivered event stores no second notice.
     *
     * 멱등 컨슈머 트랜잭션에 참여하므로, 다시 전달된 이벤트가 두 번째 알림을 저장하지 않는다.
     */
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
