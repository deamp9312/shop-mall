package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of orders: order list and detail screens (REQ-F-012) and the notification module's member lookup.
 *
 * 주문 조회 쪽: 주문 목록과 상세 화면 (REQ-F-012), 그리고 알림 모듈의 회원 조회.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    public static final int PAGE_SIZE = 10;

    private final OrderRepository orders;

    OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * @param page zero-based
     *
     * page: 0부터 시작
     */
    public Page<OrderSummary> findSummaries(long memberId, int page) {
        return orders.findByMemberIdOrderByCreatedAtDescIdDesc(memberId, PageRequest.of(Math.max(page, 0), PAGE_SIZE))
                .map(OrderSummary::of);
    }

    /**
     * The order with its lines and status history loaded, for the detail page (open-in-view is off).
     *
     * 상세 화면용으로 주문 줄과 상태 이력까지 불러온 주문 (open-in-view가 꺼져 있다).
     * 예외: ShopException ORDER_NOT_FOUND. 다른 회원의 주문도 같다 (REQ-F-012: 존재 여부를 드러내지 않는다).
     *
     * @throws ShopException ORDER_NOT_FOUND, also for another member's order (REQ-F-012: existence is not revealed)
     */
    public Order findDetail(long memberId, long orderId) {
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
        order.getItems().size();
        order.getStatusHistory().size();
        return order;
    }

    /**
     * @throws ShopException ORDER_NOT_FOUND
     *
     * 예외: ShopException ORDER_NOT_FOUND
     */
    public long memberIdOf(long orderId) {
        return orders.findById(orderId).map(Order::getMemberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }
}
