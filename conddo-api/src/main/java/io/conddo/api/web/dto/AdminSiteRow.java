package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantSite;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin-facing wire shape for the QA queue at studio.getconddo.com. Widens
 * {@link TenantSiteDto} with the tenant identity (name, slug, vertical,
 * plan) so the operator can act on a site without pivoting through a
 * separate tenants lookup — the previous DTO returned bare UUIDs and left
 * the FE showing "petens" / "amaka-fashions" (the subdomain) with no
 * business context.
 *
 * <p>Kept as its own record rather than a superset of {@link TenantSiteDto}
 * because the tenant-facing DTO stays lean (a tenant already knows who
 * they are) and the admin surface adds fields the tenant-facing endpoints
 * intentionally don't expose.
 */
public record AdminSiteRow(
        UUID id,
        UUID tenantId,
        String tenantName,           // business name — headline in the row
        String tenantSlug,           // "petens" — used for links, sorting
        String verticalId,           // "fashion" / "pharmacy" / …
        String planId,               // free / student / starter / growth / pro
        String subdomain,
        String customDomain,
        String hostingProvider,
        String siteType,
        String submittedUrl,
        boolean isActive,
        boolean qaApproved,
        OffsetDateTime qaApprovedAt,
        OffsetDateTime createdAt) {

    /** Compose from a site + its owning tenant. {@code tenant} may be null if
     *  a stale row references a deleted tenant — in which case the fields
     *  degrade to "(deleted)" so the operator can still delete/deactivate the
     *  orphan without a null-pointer crash. */
    public static AdminSiteRow of(TenantSite s, Tenant tenant) {
        String name = tenant != null ? tenant.getName() : "(deleted tenant)";
        String slug = tenant != null ? tenant.getSlug() : null;
        String vertical = tenant != null ? tenant.getVerticalId() : null;
        String plan = tenant != null ? tenant.getPlanId() : null;
        return new AdminSiteRow(
                s.getId(), s.getTenantId(),
                name, slug, vertical, plan,
                s.getSubdomain(), s.getCustomDomain(),
                s.getHostingProvider(), s.getSiteType(),
                s.getSubmittedUrl(),
                s.isActive(), s.isQaApproved(), s.getQaApprovedAt(),
                s.getCreatedAt());
    }
}
