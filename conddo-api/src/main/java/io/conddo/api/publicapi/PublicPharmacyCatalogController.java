package io.conddo.api.publicapi;

import io.conddo.core.domain.Product;
import io.conddo.core.domain.ProductCategory;
import io.conddo.core.service.PharmacyDeliveryFeeService;
import io.conddo.core.service.PublicPharmacyCatalogService;
import io.conddo.core.service.PublicPharmacyCatalogService.CatalogPage;
import io.conddo.core.service.PublicPharmacyCatalogService.CategoryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public catalog read endpoints for the merchant's pharmacy website
 * (PHARMACY_PUBLIC_API_SPEC §3 + §11). The PublicSiteInterceptor binds
 * the tenant via {@code X-Conddo-Site-Key} before these run; everything
 * here is RLS-scoped. No customer JWT required — the catalog is open to
 * anyone who has the merchant's site key.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy")
public class PublicPharmacyCatalogController {

    private final PublicPharmacyCatalogService catalog;
    private final PharmacyDeliveryFeeService deliveryFee;

    public PublicPharmacyCatalogController(PublicPharmacyCatalogService catalog,
                                           PharmacyDeliveryFeeService deliveryFee) {
        this.catalog = catalog;
        this.deliveryFee = deliveryFee;
    }

    @GetMapping("/products")
    public Map<String, Object> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) Boolean requiresPrescription,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        CatalogPage result = catalog.listProducts(category, q, featured, requiresPrescription, page, limit);
        List<Map<String, Object>> products = new ArrayList<>();
        for (Product p : result.products()) {
            ProductCategory cat = catalog.resolveCategory(p.getCategoryId());
            products.add(toProductDto(p, cat));
        }
        return Map.of(
                "products", products,
                "pagination", Map.of(
                        "page", result.page(),
                        "limit", result.limit(),
                        "total", result.total(),
                        "pages", result.totalPages()));
    }

    @GetMapping("/products/{productSlug}")
    public Map<String, Object> productDetail(@PathVariable String productSlug) {
        Product p = catalog.getProductBySlug(productSlug);
        ProductCategory cat = catalog.resolveCategory(p.getCategoryId());
        return Map.of("product", toProductDto(p, cat));
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CategoryView v : catalog.listCategories()) {
            ProductCategory c = v.category();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("name", c.getName());
            row.put("slug", c.getSlug());
            row.put("icon", c.getIcon());
            row.put("productCount", v.productCount());
            rows.add(row);
        }
        return Map.of("categories", rows);
    }

    @GetMapping("/delivery-fee")
    public Map<String, Object> deliveryFee(@RequestParam String state) {
        PharmacyDeliveryFeeService.Quote quote = deliveryFee.quote(state);
        return Map.of(
                "state", quote.state(),
                "fee", quote.fee(),
                "estimate", quote.estimate());
    }

    // ----- DTOs --------------------------------------------------------------

    /**
     * Trims to the FE-binding shape (PHARMACY_PUBLIC_API_SPEC §3) — no
     * reorderThreshold, cost, batch/expiry. Stock is the actual integer
     * (the FE shows "X in stock" for low quantities).
     */
    private static Map<String, Object> toProductDto(Product p, ProductCategory category) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("nameGeneric", p.getNameGeneric() == null ? p.getName() : p.getNameGeneric());
        m.put("nameBrand", p.getNameBrand());
        m.put("slug", p.getSlug());
        m.put("description", p.getDescription());
        m.put("indications", p.getIndications());
        m.put("dosageGuidance", p.getDosageGuidance());
        m.put("warnings", p.getWarnings());
        m.put("storage", p.getStorage());
        m.put("price", p.getPrice() == null ? BigDecimal.ZERO : p.getPrice());
        m.put("requiresPrescription", p.isRequiresPrescription());
        m.put("stockQty", p.getStock());
        m.put("nafdacNumber", p.getNafdacNumber());
        m.put("brand", p.getBrand());
        m.put("images", p.getImages() == null ? List.of() : p.getImages());
        m.put("isActive", p.isActive());
        if (category != null) {
            m.put("category", Map.of(
                    "name", category.getName(),
                    "slug", category.getSlug()));
        } else {
            m.put("category", null);
        }
        return m;
    }
}
