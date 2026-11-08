package com.example.shop.web;

import com.example.shop.cart.CartService;
import com.example.shop.product.ProductCatalog;
import com.example.shop.product.ProductInfo;
import com.example.shop.stock.AvailableStockReader;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Product detail (REQ-F-004): product information from the cache, available stock from Redis every time. */
@Controller
public class ProductPageController {

    private final ProductCatalog catalog;
    private final AvailableStockReader availableStock;

    public ProductPageController(ProductCatalog catalog, AvailableStockReader availableStock) {
        this.catalog = catalog;
        this.availableStock = availableStock;
    }

    @GetMapping("/products/{productId}")
    public String detail(@PathVariable long productId, Model model) {
        ProductInfo product = catalog.getProduct(productId);
        long available = availableStock.availableOf(productId);
        model.addAttribute("product", product);
        model.addAttribute("available", available);
        model.addAttribute("maxSelectable", Math.min(available, CartService.MAX_QUANTITY_PER_PRODUCT));
        return "product/detail";
    }
}
