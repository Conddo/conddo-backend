package io.conddo.api.publicapi;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.TenantSiteService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public read of a Conddo-managed website. The Next.js middleware detects
 * a request for {@code <slug>.getconddo.com} (or a verified custom domain),
 * calls this endpoint with the Host header as the {@code host} query param,
 * and renders the returned sections + theme.
 *
 * <p>Unauthenticated by design — every response is public HTML content
 * anyway. The {@link PublicSiteInterceptor}'s API-key check is bypassed
 * for this path (see BillingWebConfig's excluded patterns).
 *
 * <p>404 when no published managed site matches the host — the middleware
 * uses that to render Conddo's own marketing landing instead of a broken
 * tenant page.
 */
@RestController
@RequestMapping("/api/v1/public/managed-site")
public class PublicManagedSiteController {

    private final TenantSiteService siteService;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;

    public PublicManagedSiteController(TenantSiteService siteService,
                                       TenantRepository tenantRepository,
                                       TenantSession tenantSession) {
        this.siteService = siteService;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> byHost(@RequestParam("host") String host) {
        TenantSite site = siteService.resolvePublicHost(host)
                .orElseThrow(() -> new NotFoundException("No site at this address"));

        // Bind the tenant so any downstream read (product catalog fetch in the
        // future, etc.) passes RLS. Read-only, so cross-tenant is not needed.
        TenantContext.set(site.getTenantId());
        tenantSession.bind();
        Tenant tenant = tenantRepository.findById(site.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("businessName", tenant.getName());
        resp.put("verticalId", tenant.getVerticalId());
        resp.put("slug", tenant.getSlug());
        resp.put("customDomain", site.getCustomDomain());
        resp.put("sections", site.getSections());
        resp.put("theme", site.getTheme());
        resp.put("publishedAt", site.getPublishedAt() != null ? site.getPublishedAt().toString() : null);
        return ApiResponse.ok(resp);
    }
}
