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
 * Order list and detail (REQ-F-012): 10 orders per page, newest first; the detail shows lines, address, the mock
 * payment, the status timeline with its cause events, payment notices (REQ-F-009 4) and the cancel button
 * (REQ-F-011). Another member's order is a 404 page.
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
