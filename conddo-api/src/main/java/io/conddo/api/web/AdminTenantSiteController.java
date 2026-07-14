package io.conddo.api.web;

import io.conddo.api.web.dto.AdminSiteRow;
import io.conddo.api.web.dto.TenantSiteDto;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.TenantSiteService;
import io.conddo.core.tenant.TenantSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Platform-staff endpoints for the tenant-website QA pipeline
 * (WEBSITE_INTEGRATION_SPEC §4). Restricted to {@code SUPER_ADMIN} — these
 * mutate state across tenants and rely on the {@code app.cross_tenant} RLS
 * carve-out added in V26.
 *
 * <p>{@code STAFF} reads are intentionally not granted here; review queues
 * for STAFF roles can be layered later by widening {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/admin/sites")
public class AdminTenantSiteController {

    private static final String STAFF = "hasRole('SUPER_ADMIN')";

    private final TenantSiteService service;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;

    public AdminTenantSiteController(TenantSiteService service,
                                     TenantRepository tenantRepository,
                                     TenantSession tenantSession) {
        this.service = service;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
    }

    /** QA queue. {@code filter} = {@code pending} (default) | approved | active | all.
     *  Returns rows enriched with the owning tenant's identity (name, slug,
     *  vertical, plan) — a bare site id + subdomain was uninformative in the
     *  admin UI. Batch-loads tenants with a single {@code findAllById} to
     *  avoid N+1. */
    @GetMapping
    @PreAuthorize(STAFF)
    @Transactional(readOnly = true)
    public ApiResponse<List<AdminSiteRow>> list(
            @RequestParam(defaultValue = "pending") String filter) {
        TenantSiteService.SiteFilter parsed = parse(filter);
        List<TenantSite> sites = service.listForReview(parsed);

        // Cross-tenant read — the sites already came back under the
        // cross_tenant carve-out set by TenantSiteService.listForReview.
        // Bind again here because Spring's @Transactional opened a new tx
        // for this method AFTER the service's tx closed, and RLS clears
        // the GUC at commit. Without this bind the tenants query returns 0.
        tenantSession.bindCrossTenant();
        Set<UUID> tenantIds = sites.stream()
                .map(TenantSite::getTenantId).collect(Collectors.toSet());
        Map<UUID, Tenant> tenantsById = new HashMap<>();
        for (Tenant t : tenantRepository.findAllById(tenantIds)) {
            tenantsById.put(t.getId(), t);
        }
        List<AdminSiteRow> rows = sites.stream()
                .map(s -> AdminSiteRow.of(s, tenantsById.get(s.getTenantId())))
                .toList();
        return ApiResponse.ok(rows);
    }

    /** Approve + activate the site. Idempotent. */
    @PostMapping("/{id}/approve")
    @PreAuthorize(STAFF)
    public ApiResponse<TenantSiteDto> approve(@PathVariable UUID id,
                                              @AuthenticationPrincipal Object principal) {
        UUID staffId = staffIdFromPrincipal(principal);
        TenantSite updated = service.approve(id, staffId);
        return ApiResponse.ok(TenantSiteDto.masked(updated));
    }

    /** Take a site down ({@code is_active=false}). qa_approved stays true. */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize(STAFF)
    public ApiResponse<TenantSiteDto> deactivate(@PathVariable UUID id) {
        TenantSite updated = service.deactivate(id);
        return ApiResponse.ok(TenantSiteDto.masked(updated));
    }

    private static TenantSiteService.SiteFilter parse(String raw) {
        if (raw == null) {
            return TenantSiteService.SiteFilter.PENDING;
        }
        return switch (raw.toLowerCase()) {
            case "approved" -> TenantSiteService.SiteFilter.APPROVED;
            case "active" -> TenantSiteService.SiteFilter.ACTIVE;
            case "all" -> TenantSiteService.SiteFilter.ALL;
            default -> TenantSiteService.SiteFilter.PENDING;
        };
    }

    /**
     * Pulls the staff UUID out of whatever the security filter chain stored as
     * the principal. The platform's JWT filter (§1a) sets the principal to the
     * authenticated user id; the recorded reviewer is informational only, so a
     * null/unknown shape is logged as approval-by-system rather than failing
     * the call.
     */
    @SuppressWarnings("unchecked")
    private static UUID staffIdFromPrincipal(Object principal) {
        if (principal == null) {
            return null;
        }
        if (principal instanceof UUID id) {
            return id;
        }
        String s = principal.toString();
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
