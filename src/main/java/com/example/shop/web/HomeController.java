package com.example.shop.web;

import com.example.shop.product.ProductCatalog;
import com.example.shop.product.ProductInfo;
import com.example.shop.product.ProductSearchCondition;
import com.example.shop.product.ProductSort;
import com.example.shop.stock.AvailableStockReader;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** Main page = product listing with category, name search, sort and 12-per-page paging (REQ-F-003). */
@Controller
public class HomeController {

    private final ProductCatalog catalog;
    private final AvailableStockReader availableStock;

    public HomeController(ProductCatalog catalog, AvailableStockReader availableStock) {
        this.catalog = catalog;
        this.availableStock = availableStock;
    }

    /** @param page one-based page number as shown on screen */
    @GetMapping("/")
    public String home(@RequestParam(required = false) Long category,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "LATEST") ProductSort sort,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        ProductSearchCondition condition = new ProductSearchCondition(category, q, sort);
        Page<ProductInfo> products = catalog.search(condition, Math.max(page, 1) - 1);
        Map<Long, Long> available = availableStock.availableOf(
                products.getContent().stream().map(ProductInfo::id).toList());
        model.addAttribute("products", products);
        model.addAttribute("available", available);
        model.addAttribute("categories", catalog.categories());
        model.addAttribute("condition", condition);
        return "index";
    }
}
