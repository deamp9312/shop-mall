package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 주문 조회 측: 주문 목록·상세 화면(REQ-F-012)과 알림 모듈의 회원 조회를 담당한다. */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    public static final int PAGE_SIZE = 10;

    private final OrderRepository orders;

    OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    /** @param page 0부터 시작한다 */
    public Page<OrderSummary> findSummaries(long memberId, int page) {
        return orders.findByMemberIdOrderByCreatedAtDescIdDesc(memberId, PageRequest.of(Math.max(page, 0), PAGE_SIZE))
                .map(OrderSummary::of);
    }

    /**
     * 상세 페이지용으로 주문 라인과 상태 이력을 로딩한 주문을 반환한다 (open-in-view가 꺼져 있다).
     *
     * @throws ShopException ORDER_NOT_FOUND, 다른 회원의 주문인 경우에도 마찬가지다 (REQ-F-012: 존재 여부를 드러내지 않는다)
     */
    public Order findDetail(long memberId, long orderId) {
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
        order.getItems().size();
        order.getStatusHistory().size();
        return order;
    }

    /** @throws ShopException ORDER_NOT_FOUND */
    public long memberIdOf(long orderId) {
        return orders.findById(orderId).map(Order::getMemberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }
}
