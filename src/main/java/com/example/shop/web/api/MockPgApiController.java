package com.example.shop.web.api;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.order.OrderService;
import com.example.shop.payment.MockPgApprovalService;
import com.example.shop.payment.MockPgResult;
import com.example.shop.payment.PaymentMethod;
import com.example.shop.payment.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock PG approval API (REQ-IF-001 5, REQ-F-008 3). No card or account number is ever accepted; the method is for
 * display only. The idempotency key of a payment is its order id (REQ-SE-002 3), so the Idempotency-Key header must
 * carry the order id. 200 with the settled status, 202 while a delayed answer is pending.
 */
@RestController
public class MockPgApiController {

    private final OrderService orderService;
    private final MockPgApprovalService approval;

    public MockPgApiController(OrderService orderService, MockPgApprovalService approval) {
        this.orderService = orderService;
        this.approval = approval;
    }

    @PostMapping("/mock-pg/approve")
    public ResponseEntity<ApproveResponse> approve(@AuthenticationPrincipal MemberPrincipal member,
                                                   @RequestHeader(OrderApiController.IDEMPOTENCY_KEY_HEADER)
                                                   String idempotencyKey,
                                                   @Valid @RequestBody ApproveRequest request) {
        if (!String.valueOf(request.orderId()).equals(idempotencyKey)) {
            throw new ShopException(ErrorCode.INVALID_INPUT);
        }
        orderService.findOwned(member.getMemberId(), request.orderId());
        PaymentStatus status = approval.approve(request.orderId(), request.result(), request.methodOrDefault());
        HttpStatus httpStatus = status == PaymentStatus.READY ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(new ApproveResponse(request.orderId(), status.name()));
    }

    public record ApproveRequest(@NotNull Long orderId, @NotNull MockPgResult result, PaymentMethod method) {

        PaymentMethod methodOrDefault() {
            return method == null ? PaymentMethod.CARD : method;
        }
    }

    public record ApproveResponse(long orderId, String paymentStatus) {
    }
}
