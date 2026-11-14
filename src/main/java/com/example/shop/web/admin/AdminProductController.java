package com.example.shop.web.admin;

import com.example.shop.common.ShopException;
import com.example.shop.product.ProductAdminService;
import com.example.shop.product.ProductCatalog;
import com.example.shop.product.ProductDetails;
import com.example.shop.product.ProductInfo;
import com.example.shop.product.SampleImages;
import com.example.shop.stock.AvailableStockReader;
import com.example.shop.stock.StockAdjustmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * Admin product and stock management screens (REQ-F-013). ADMIN only, enforced by SecurityConfig.
 *
 * 관리자 상품·재고 관리 화면 (REQ-F-013). ADMIN 전용이며 SecurityConfig가 강제한다.
 */
@Controller
@RequestMapping("/admin")
public class AdminProductController {

    private static final int MAX_STOCK = 1_000_000;

    private final ProductAdminService productAdmin;
    private final ProductCatalog catalog;
    private final StockAdjustmentService stockAdjustment;
    private final AvailableStockReader availableStock;
    private final SampleImages sampleImages;

    public AdminProductController(ProductAdminService productAdmin, ProductCatalog catalog,
                                  StockAdjustmentService stockAdjustment, AvailableStockReader availableStock,
                                  SampleImages sampleImages) {
        this.productAdmin = productAdmin;
        this.catalog = catalog;
        this.stockAdjustment = stockAdjustment;
        this.availableStock = availableStock;
        this.sampleImages = sampleImages;
    }

    @GetMapping
    public String home() {
        return "redirect:/admin/products";
    }

    @GetMapping("/products")
    public String list(Model model) {
        List<ProductInfo> products = productAdmin.findAll();
        Map<Long, Long> available = availableStock.availableOf(products.stream().map(ProductInfo::id).toList());
        model.addAttribute("products", products);
        model.addAttribute("available", available);
        return "admin/products";
    }

    @GetMapping("/products/new")
    public String newForm(@ModelAttribute("form") ProductForm form, Model model) {
        return formPage(model, null);
    }

    @PostMapping("/products")
    public String create(@Valid @ModelAttribute("form") ProductForm form, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (!validImage(form, binding)) {
            return formPage(model, null);
        }
        Long productId = productAdmin.create(form.getCategoryId(), details(form));
        redirect.addFlashAttribute("notice", "상품을 등록했습니다. 재고는 0개이므로 재고를 조정해 주세요.");
        return "redirect:/admin/products/" + productId + "/edit";
    }

    @GetMapping("/products/{productId}/edit")
    public String editForm(@PathVariable long productId, Model model) {
        ProductInfo product = productAdmin.get(productId);
        ProductForm form = new ProductForm();
        form.setCategoryId(product.categoryId());
        form.setName(product.name());
        form.setDescription(product.description());
        form.setPrice(product.price());
        form.setImageFileName(sampleImages.fileNameOf(product.imageUrl()));
        model.addAttribute("form", form);
        return formPage(model, product);
    }

    @PostMapping("/products/{productId}")
    public String update(@PathVariable long productId, @Valid @ModelAttribute("form") ProductForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        if (!validImage(form, binding)) {
            return formPage(model, productAdmin.get(productId));
        }
        productAdmin.update(productId, form.getCategoryId(), details(form));
        redirect.addFlashAttribute("notice", "상품 정보를 수정했습니다.");
        return "redirect:/admin/products/" + productId + "/edit";
    }

    @PostMapping("/products/{productId}/stop-sale")
    public String stopSale(@PathVariable long productId, RedirectAttributes redirect) {
        productAdmin.stopSale(productId);
        redirect.addFlashAttribute("notice", "판매를 중지했습니다.");
        return "redirect:/admin/products";
    }

    @PostMapping("/products/{productId}/stock")
    public String adjustStock(@PathVariable long productId, @RequestParam int newStock,
                              RedirectAttributes redirect) {
        if (newStock < 0 || newStock > MAX_STOCK) {
            redirect.addFlashAttribute("error", "재고는 0 ~ " + MAX_STOCK + " 사이로 입력해 주세요.");
            return "redirect:/admin/products/" + productId + "/edit";
        }
        try {
            stockAdjustment.adjust(productId, newStock);
            redirect.addFlashAttribute("notice", "재고를 " + newStock + "개로 조정했습니다.");
        } catch (ShopException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/products/" + productId + "/edit";
    }

    private String formPage(Model model, ProductInfo product) {
        model.addAttribute("product", product);
        model.addAttribute("categories", catalog.categories());
        model.addAttribute("images", sampleImages.fileNames());
        if (product != null) {
            model.addAttribute("available", availableStock.availableOf(product.id()));
        }
        return "admin/product-form";
    }

    private boolean validImage(ProductForm form, BindingResult binding) {
        if (form.getImageFileName() != null && !sampleImages.exists(form.getImageFileName())) {
            binding.rejectValue("imageFileName", "unknown", "목록에 있는 샘플 이미지를 선택해 주세요.");
        }
        return !binding.hasErrors();
    }

    private ProductDetails details(ProductForm form) {
        return new ProductDetails(form.getName().trim(), form.getDescription().trim(), form.getPrice(),
                sampleImages.urlOf(form.getImageFileName()));
    }

    public static class ProductForm {

        @NotNull
        private Long categoryId;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotBlank
        @Size(max = 2000)
        private String description;

        @NotNull
        @Min(0)
        @Max(100_000_000)
        private Long price;

        @NotBlank
        private String imageFileName;

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Long getPrice() {
            return price;
        }

        public void setPrice(Long price) {
            this.price = price;
        }

        public String getImageFileName() {
            return imageFileName;
        }

        public void setImageFileName(String imageFileName) {
            this.imageFileName = imageFileName;
        }
    }
}
