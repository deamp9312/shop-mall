package com.example.shop.web;

import com.example.shop.cart.CartLine;
import com.example.shop.cart.CartService;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.product.ProductCatalog;
import com.example.shop.product.ProductInfo;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Order form (REQ-F-006 1). Opened from the cart (orderable lines only) or with "buy now" (productId + qty).
 * Every visit gets a new idempotency key that the form sends as the Idempotency-Key header (REQ-SE-002 1).
 */
@Controller
public class OrderPageController {

    private final CartService cartService;
    private final ProductCatalog catalog;

    public OrderPageController(CartService cartService, ProductCatalog catalog) {
        this.cartService = cartService;
        this.catalog = catalog;
    }

    @GetMapping("/orders/checkout")
    public String checkout(@AuthenticationPrincipal MemberPrincipal member,
                           @RequestParam(required = false) Long productId,
                           @RequestParam(defaultValue = "1") int qty, Model model) {
        boolean fromCart = productId == null;
        List<CheckoutLine> lines = fromCart ? orderableCartLines(member.getMemberId()) : buyNowLine(productId, qty);
        model.addAttribute("lines", lines);
        model.addAttribute("totalAmount", lines.stream().mapToLong(CheckoutLine::subtotal).sum());
        model.addAttribute("fromCart", fromCart);
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "order/checkout";
    }

    private List<CheckoutLine> orderableCartLines(long memberId) {
        return cartService.view(memberId).lines().stream()
                .filter(CartLine::orderable)
                .map(line -> new CheckoutLine(line.productId(), line.productName(), line.unitPrice(), line.quantity()))
                .toList();
    }

    private List<CheckoutLine> buyNowLine(long productId, int qty) {
        ProductInfo product = catalog.getProduct(productId);
        int quantity = Math.max(1, Math.min(qty, CartService.MAX_QUANTITY_PER_PRODUCT));
        return List.of(new CheckoutLine(product.id(), product.name(), product.price(), quantity));
    }
}
