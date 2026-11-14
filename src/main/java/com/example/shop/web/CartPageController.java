package com.example.shop.web;

import com.example.shop.cart.CartService;
import com.example.shop.member.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Cart screen (REQ-F-005 3). Changes are made with fetch calls to /api/cart.
 *
 * 장바구니 화면 (REQ-F-005 3). 변경은 /api/cart로 fetch 호출해서 한다.
 */
@Controller
public class CartPageController {

    private final CartService cartService;

    public CartPageController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/cart")
    public String cart(@AuthenticationPrincipal MemberPrincipal member, Model model) {
        model.addAttribute("cart", cartService.view(member.getMemberId()));
        model.addAttribute("maxQuantity", CartService.MAX_QUANTITY_PER_PRODUCT);
        return "cart/cart";
    }
}
