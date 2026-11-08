package com.example.shop.web;

import com.example.shop.member.MemberPrincipal;
import com.example.shop.order.Order;
import com.example.shop.order.OrderService;
import com.example.shop.order.OrderStatus;
import com.example.shop.payment.PaymentMethod;
import com.example.shop.payment.PaymentQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Mock payment screen (REQ-F-008 2, 5). Only the owner's order opens (404 otherwise). Without a READY payment yet
 * (order.created not consumed) the page shows "결제 준비 중" and polls; after the deadline the buttons are disabled.
 */
@Controller
public class PaymentPageController {

    private final OrderService orderService;
    private final PaymentQueryService payments;

    public PaymentPageController(OrderService orderService, PaymentQueryService payments) {
        this.orderService = orderService;
        this.payments = payments;
    }

    @GetMapping("/payments/{orderId}")
    public String payment(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long orderId, Model model) {
        Order order = orderService.findOwned(member.getMemberId(), orderId);
        boolean expired = !LocalDateTime.now().isBefore(order.getPaymentExpiresAt());
        model.addAttribute("order", order);
        model.addAttribute("payment", payments.findPayment(orderId).orElse(null));
        model.addAttribute("expiresAtEpochMillis",
                order.getPaymentExpiresAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        model.addAttribute("payable", order.getStatus() == OrderStatus.PAYMENT_PENDING && !expired);
        model.addAttribute("methods", PaymentMethod.values());
        return "payment/pay";
    }
}
