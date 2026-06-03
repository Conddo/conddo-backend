package io.conddo.studio.web.dto;

import io.conddo.studio.platform.PlatformAdminService;
import io.conddo.studio.platform.PlatformTenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape for the platform-admin tenant endpoints (§23.3). Two flavours:
 * {@link #summary(PlatformTenant)} is the list-row view, {@link #detail(PlatformAdminService.TenantWithCounts)}
 * adds the user + job counts shown on the tenant detail card.
 */
public record PlatformTenantDto(
        UUID id,
        String name,
        String slug,
        String verticalId,
        String planId,
        String customDomain,
        String status,
        String websiteStatus,
        OffsetDateTime websitePublishedAt,
        OffsetDateTime createdAt,
        Counts counts) {

    public static PlatformTenantDto summary(PlatformTenant tenant) {
        return new PlatformTenantDto(tenant.getId(), tenant.getName(), tenant.getSlug(),
                tenant.getVerticalId(), tenant.getPlanId(), tenant.getCustomDomain(),
                tenant.getStatus(), tenant.getWebsiteStatus(), tenant.getWebsitePublishedAt(),
                tenant.getCreatedAt(), null);
    }

    public static PlatformTenantDto detail(PlatformAdminService.TenantWithCounts view) {
        PlatformTenant t = view.tenant();
        return new PlatformTenantDto(t.getId(), t.getName(), t.getSlug(),
                t.getVerticalId(), t.getPlanId(), t.getCustomDomain(),
                t.getStatus(), t.getWebsiteStatus(), t.getWebsitePublishedAt(),
                t.getCreatedAt(),
                new Counts(view.totalUsers(), view.activeUsers(), view.activeJobs(), view.deliveredJobs()));
    }

    public record Counts(long users, long activeUsers, long activeJobs, long deliveredJobs) {
    }
}
