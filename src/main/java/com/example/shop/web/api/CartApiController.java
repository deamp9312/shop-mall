package com.example.shop.web.api;

import com.example.shop.cart.CartService;
import com.example.shop.cart.CartView;
import com.example.shop.member.MemberPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart REST API (REQ-IF-001 1, REQ-F-005). Quantity per request is 1..10; more than 10 is 400.
 *
 * 장바구니 REST API (REQ-IF-001 1, REQ-F-005). 요청당 수량은 1..10이며 10을 넘으면 400.
 */
@RestController
@RequestMapping("/api/cart")
public class CartApiController {

    private final CartService cartService;

    public CartApiController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartView get(@AuthenticationPrincipal MemberPrincipal member) {
        return cartService.view(member.getMemberId());
    }

    @PostMapping("/items")
    public CartView add(@AuthenticationPrincipal MemberPrincipal member, @Valid @RequestBody AddItemRequest request) {
        cartService.add(member.getMemberId(), request.productId(), request.qty());
        return cartService.view(member.getMemberId());
    }

    @PatchMapping("/items/{productId}")
    public CartView changeQuantity(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long productId,
                                   @Valid @RequestBody QuantityRequest request) {
        cartService.changeQuantity(member.getMemberId(), productId, request.qty());
        return cartService.view(member.getMemberId());
    }

    @DeleteMapping("/items/{productId}")
    public CartView remove(@AuthenticationPrincipal MemberPrincipal member, @PathVariable long productId) {
        cartService.remove(member.getMemberId(), productId);
        return cartService.view(member.getMemberId());
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal MemberPrincipal member) {
        cartService.clear(member.getMemberId());
        return ResponseEntity.noContent().build();
    }

    public record AddItemRequest(@NotNull Long productId,
                                 @NotNull @Min(1) @Max(CartService.MAX_QUANTITY_PER_PRODUCT) Integer qty) {
    }

    public record QuantityRequest(@NotNull @Min(1) @Max(CartService.MAX_QUANTITY_PER_PRODUCT) Integer qty) {
    }
}
