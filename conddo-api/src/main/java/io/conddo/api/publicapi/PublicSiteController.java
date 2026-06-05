package io.conddo.api.publicapi;

import io.conddo.api.publicapi.dto.PublicOrderRequest;
import io.conddo.api.publicapi.dto.PublicProduct;
import io.conddo.api.publicapi.dto.PublicStoreInfo;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.BillingService;
import io.conddo.core.service.OrderService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public-site surface (WEBSITE_INTEGRATION_SPEC §3). Authenticated by the
 * {@link PublicSiteInterceptor} (header API key + bcrypt + rate limit +
 * tenant bind), so every method here runs with {@code TenantContext} already
 * set and an active {@link PublicSiteAuth} on the request.
 *
 * <p>Module gating mirrors the dashboard's — Launcher pharmacies can't expose
 * order intake even if their website tries to call it. The public 403 body
 * intentionally omits the dashboard's "Upgrade" CTA: the customer browsing
 * the merchant's website can't upgrade the merchant's plan.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}")
public class PublicSiteController {

    private final TenantRepository tenantRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final OrderService orderService;
    private final BillingService billingService;
    private final TenantSession tenantSession;

    @PersistenceContext
    private EntityManager entityManager;

    public PublicSiteController(TenantRepository tenantRepository,
                                ProductRepository productRepository,
                                CustomerRepository customerRepository,
                                OrderService orderService,
                                BillingService billingService,
                                TenantSession tenantSession) {
        this.tenantRepository = tenantRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.orderService = orderService;
        this.billingService = billingService;
        this.tenantSession = tenantSession;
    }

    @GetMapping("/store-info")
    @Transactional(readOnly = true)
    public ApiResponse<PublicStoreInfo> storeInfo(@PathVariable String slug) {
        Tenant tenant = tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new IllegalStateException("Tenant vanished after resolve"));
        return ApiResponse.ok(buildStoreInfo(tenant));
    }

    /** Pharmacy catalog (always JSON list). Gated by {@code order_management}. */
    @GetMapping("/pharmacy/products")
    @Transactional(readOnly = true)
    public ApiResponse<List<PublicProduct>> products(@PathVariable String slug) {
        if (!billingService.hasFeature(TenantContext.require(), "order_management")) {
            throw new ModuleNotEnabled("Catalog browsing isn't enabled on the merchant's plan.");
        }
        tenantSession.bind();
        List<PublicProduct> rows = new ArrayList<>();
        for (Product p : productRepository.findAll()) {
            if (p.isActive()) {
                rows.add(PublicProduct.from(p));
            }
        }
        return ApiResponse.ok(rows);
    }

    /**
     * Public order intake (WEBSITE_INTEGRATION_SPEC §3 stock-race). Per line:
     * <ol>
     *   <li>SELECT … FOR UPDATE on the product (pessimistic lock).</li>
     *   <li>Re-verify {@code stock >= requested} after the lock.</li>
     *   <li>On any shortage, throw — Spring rolls back the whole transaction.</li>
     *   <li>Decrement stock + persist the Order via {@link OrderService}.</li>
     * </ol>
     * Returns 409 {@code STOCK_SHORTAGE} with a per-line breakdown.
     */
    @PostMapping("/pharmacy/orders")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrder(
            @PathVariable String slug,
            @Valid @RequestBody PublicOrderRequest request) {

        if (!billingService.hasFeature(TenantContext.require(), "order_management")) {
            throw new ModuleNotEnabled("Online orders aren't enabled on the merchant's plan.");
        }
        tenantSession.bind();

        List<Map<String, Object>> shortages = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        List<OrderService.NewItem> orderItems = new ArrayList<>();
        for (PublicOrderRequest.Item line : request.items()) {
            Product p = entityManager.find(Product.class, line.productId(), LockModeType.PESSIMISTIC_WRITE);
            if (p == null) {
                shortages.add(Map.of(
                        "productId", line.productId(),
                        "available", 0,
                        "requested", line.quantity()));
                continue;
            }
            if (p.getStock() < line.quantity()) {
                shortages.add(Map.of(
                        "productId", line.productId(),
                        "available", p.getStock(),
                        "requested", line.quantity()));
                continue;
            }
            p.adjustStock(-line.quantity());
            productRepository.save(p);
            BigDecimal lineTotal = p.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
            total = total.add(lineTotal);
            orderItems.add(new OrderService.NewItem(p.getName(), line.quantity(), p.getPrice()));
        }
        if (!shortages.isEmpty()) {
            // Spring rolls back the @Transactional method on RuntimeException —
            // every stock decrement above is undone.
            throw new StockShortage(shortages);
        }

        // Public intake creates a fresh Customer row each time. Matching on
        // email/phone is a Phase 2 concern (dedup needs the merchant's call).
        Customer customer = customerRepository.save(new Customer(
                TenantContext.require(),
                request.customer().fullName(),
                request.customer().email(),
                request.customer().phone(),
                request.customer().notes()));

        Order created = orderService.create(
                customer.getId(),
                customer.getFullName(),
                "Online order",
                "Received",
                total,
                LocalDate.now(),
                orderItems,
                new LinkedHashMap<>(),   // no measurements on a pharmacy order
                request.deliveryAddress() == null ? null : "Delivery: " + request.deliveryAddress());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", created.getId());
        body.put("status", "Received");
        body.put("total", total);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    // ----- error path mappings (handled by GlobalExceptionHandler) -----------

    /** Stock race lost — 409 with the per-line breakdown the FE renders. */
    public static class StockShortage extends RuntimeException {
        private final List<Map<String, Object>> items;

        public StockShortage(List<Map<String, Object>> items) {
            super("STOCK_SHORTAGE");
            this.items = items;
        }

        public List<Map<String, Object>> getItems() {
            return items;
        }
    }

    /** Module disabled on this tenant's plan — 403 with a customer-facing message. */
    public static class ModuleNotEnabled extends RuntimeException {
        public ModuleNotEnabled(String message) {
            super(message);
        }
    }

    private PublicStoreInfo buildStoreInfo(Tenant t) {
        Map<String, Object> social = t.getSocialHandles();
        PublicStoreInfo.Social s = new PublicStoreInfo.Social(
                socialHandle(social, "instagram"),
                socialHandle(social, "facebook"),
                socialHandle(social, "twitter"),
                socialHandle(social, "linkedin"));
        Map<String, Object> loc = t.getLocation();
        return new PublicStoreInfo(
                t.getName(),
                t.getTagline(),
                t.getLogoUrl(),
                locField(loc, "street"),
                locField(loc, "city"),
                locField(loc, "state"),
                t.getContactPhone(),
                t.getContactEmail(),
                null,
                s);
    }

    private static String socialHandle(Map<String, Object> social, String key) {
        if (social == null) {
            return null;
        }
        Object v = social.get(key);
        return v == null ? null : v.toString();
    }

    private static String locField(Map<String, Object> loc, String key) {
        if (loc == null) {
            return null;
        }
        Object v = loc.get(key);
        return v == null ? null : v.toString();
    }
}
