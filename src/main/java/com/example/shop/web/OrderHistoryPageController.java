package com.example.shop.web;

import com.example.shop.member.MemberPrincipal;
import com.example.shop.notification.NotificationService;
import com.example.shop.order.Order;
import com.example.shop.order.OrderQueryService;
import com.example.shop.payment.PaymentQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 주문 목록과 상세(REQ-F-012). 목록은 페이지당 10건, 최신순이다. 상세에는 주문 라인, 배송지, mock 결제, 원인 이벤트가
 * 포함된 상태 타임라인, 결제 알림(REQ-F-009 4), 취소 버튼(REQ-F-011)을 보여준다. 다른 회원의 주문은 404 페이지가
 * 된다.
 */
@Controller
public class OrderHistoryPageController {

    private final OrderQueryService orders;
    private final PaymentQueryService payments;
    private final NotificationService notifications;

    public OrderHistoryPageController(OrderQueryService orders, PaymentQueryService payments,
                                      NotificationService notifications) {
        this.orders = orders;
        this.payments = payments;
        this.notifications = notifications;
    }

    @GetMapping("/orders")
    public String list(@AuthenticationPrincipal MemberPrincipal member, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("orders", orders.findSummaries(member.getMemberId(), page));
        return "order/list";
    }

    @GetMapping("/orders/{orderId}")
    public String detail(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long orderId, Model model) {
        Order order = orders.findDetail(member.getMemberId(), orderId);
        model.addAttribute("order", order);
        model.addAttribute("payment", payments.findPayment(orderId).orElse(null));
        model.addAttribute("notifications", notifications.findByOrder(orderId));
        return "order/detail";
    }
}
