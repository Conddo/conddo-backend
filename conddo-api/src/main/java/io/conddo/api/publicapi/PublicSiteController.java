package io.conddo.api.publicapi;

import io.conddo.api.publicapi.dto.PublicOrderRequest;
import io.conddo.api.publicapi.dto.PublicProduct;
import io.conddo.api.publicapi.dto.PublicStoreInfo;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.Tenant;
import io.conddo.core.events.OrderCreatedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    @PersistenceContext
    private EntityManager entityManager;

    public PublicSiteController(TenantRepository tenantRepository,
                                ProductRepository productRepository,
                                CustomerRepository customerRepository,
                                OrderService orderService,
                                BillingService billingService,
                                TenantSession tenantSession,
                                ApplicationEventPublisher events) {
        this.tenantRepository = tenantRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.orderService = orderService;
        this.billingService = billingService;
        this.tenantSession = tenantSession;
        this.events = events;
    }

    @GetMapping("/store-info")
    @Transactional(readOnly = true)
    public ApiResponse<PublicStoreInfo> storeInfo(@PathVariable String slug) {
        Tenant tenant = tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new IllegalStateException("Tenant vanished after resolve"));
        return ApiResponse.ok(buildStoreInfo(tenant));
    }

    // Phase-1 GET /pharmacy/products is superseded by the full
    // PHARMACY_PUBLIC_API_SPEC §3 implementation in
    // PublicPharmacyCatalogController — it returns the rich product
    // shape (slug, brand, indications, requiresPrescription, ...)
    // with the spec's pagination + filter contract.

    // Phase-1 POST /pharmacy/orders is superseded by the customer-JWT-scoped
    // checkout flow in PublicCustomerOrderController (V33). The new endpoint
    // requires a logged-in customer, pulls address + prescription gating,
    // and calculates delivery fee from the saved address. The Phase-1
    // anonymous-buyer model didn't survive contact with the spec.

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
