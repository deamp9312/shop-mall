package com.example.shop.web.api;

import com.example.shop.cart.CartService;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.order.Order;
import com.example.shop.order.OrderCancellationService;
import com.example.shop.order.OrderRequestLine;
import com.example.shop.order.OrderService;
import com.example.shop.order.ShippingInfo;
import com.example.shop.payment.PaymentQueryService;
import com.example.shop.payment.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Order REST API (REQ-IF-001 2, 3, 4). The body carries no prices; any amount the client adds is ignored and the server
 * prices the order (REQ-F-006). Another member's order is answered with 404 (REQ-SE-001 2).
 *
 * 주문 REST API (REQ-IF-001 2, 3, 4). 본문에는 가격이 없다. 클라이언트가 넣은 금액은 무시하고 서버가 주문 가격을
 * 계산한다 (REQ-F-006). 다른 회원의 주문은 404로 응답한다 (REQ-SE-001 2).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderApiController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OrderService orderService;
    private final CartService cartService;
    private final PaymentQueryService payments;
    private final OrderCancellationService cancellation;

    public OrderApiController(OrderService orderService, CartService cartService, PaymentQueryService payments,
                              OrderCancellationService cancellation) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.payments = payments;
        this.cancellation = cancellation;
    }

    @PostMapping
    public PlaceOrderResponse place(@AuthenticationPrincipal MemberPrincipal member,
                                    @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                    @Valid @RequestBody PlaceOrderRequest request) {
        long orderId = orderService.place(member.getMemberId(), idempotencyKey, request.orderLines(),
                request.address().toShippingInfo());
        if (request.fromCart()) {
            cartService.removeAll(member.getMemberId(), request.productIds());
        }
        return new PlaceOrderResponse(orderId, "/payments/" + orderId);
    }

    @GetMapping("/{orderId}/status")
    public OrderStatusResponse status(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long orderId) {
        Order order = orderService.findOwned(member.getMemberId(), orderId);
        PaymentStatus paymentStatus = payments.findStatus(orderId).orElse(null);
        return new OrderStatusResponse(order.getId(), order.getStatus().name(),
                paymentStatus == null ? null : paymentStatus.name());
    }

    /**
     * REQ-F-011: PAYMENT_PENDING or PAID only (409 ORDER_NOT_CANCELLABLE otherwise, 404 for another member's order).
     * Stock and payment follow through order.cancelled, so the answer shows the order already CANCELLED and the
     * payment status as it is at that moment.
     *
     * REQ-F-011: PAYMENT_PENDING이나 PAID만 취소할 수 있다 (그 밖에는 409 ORDER_NOT_CANCELLABLE, 다른 회원의 주문은
     * 404).
     * 재고와 결제는 order.cancelled를 따라 처리되므로, 응답에는 이미 CANCELLED인 주문과 그 순간의 결제 상태가 보인다.
     */
    @PostMapping("/{orderId}/cancel")
    public OrderStatusResponse cancel(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long orderId) {
        cancellation.cancelByMember(member.getMemberId(), orderId);
        return status(member, orderId);
    }

    public record PlaceOrderRequest(@NotEmpty @Size(max = 30) List<@NotNull @Valid Item> items,
                                    @NotNull @Valid Address address,
                                    boolean fromCart) {

        List<OrderRequestLine> orderLines() {
            return items.stream().map(item -> new OrderRequestLine(item.productId(), item.qty())).toList();
        }

        List<Long> productIds() {
            return items.stream().map(Item::productId).toList();
        }
    }

    public record Item(@NotNull Long productId, @NotNull @Min(1) @Max(CartService.MAX_QUANTITY_PER_PRODUCT) Integer qty) {
    }

    /**
     * Receiver and address text, only validated (REQ-F-006 1). Never logged (REQ-SE-003).
     *
     * 수령인과 주소 텍스트. 검증만 한다 (REQ-F-006 1). 로그에 남기지 않는다 (REQ-SE-003).
     */
    public record Address(@NotBlank @Size(max = 50) String receiverName,
                          @NotBlank @Pattern(regexp = "[0-9-]{9,20}") String receiverPhone,
                          @NotBlank @Size(max = 255) String line) {

        ShippingInfo toShippingInfo() {
            return new ShippingInfo(receiverName.strip(), receiverPhone, line.strip());
        }

        @Override
        public String toString() {
            return "Address[masked]";
        }
    }

    public record PlaceOrderResponse(long orderId, String paymentUrl) {
    }

    public record OrderStatusResponse(long orderId, String status, String paymentStatus) {
    }
}
