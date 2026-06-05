package io.conddo.api.web.dto;

import io.conddo.core.domain.TenantSite;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape matching the FE's {@code TenantSite} type
 * (WEBSITE_INTEGRATION_SPEC §2). {@code apiKey} is non-null ONLY immediately
 * after {@code POST /website/site/regenerate-key} — every other read returns
 * {@code apiKey: null} with {@code apiKeyMasked} populated.
 */
public record TenantSiteDto(
        UUID id,
        UUID tenantId,
        String subdomain,
        String customDomain,
        String hostingProvider,
        String siteType,
        String apiKey,
        String apiKeyMasked,
        boolean isActive,
        boolean qaApproved,
        OffsetDateTime qaApprovedAt,
        String submittedUrl,
        OffsetDateTime createdAt) {

    public static TenantSiteDto masked(TenantSite s) {
        return new TenantSiteDto(s.getId(), s.getTenantId(), s.getSubdomain(), s.getCustomDomain(),
                s.getHostingProvider(), s.getSiteType(), null, s.maskedKey(),
                s.isActive(), s.isQaApproved(), s.getQaApprovedAt(), s.getSubmittedUrl(),
                s.getCreatedAt());
    }

    public static TenantSiteDto withPlaintext(TenantSite s, String plaintext) {
        return new TenantSiteDto(s.getId(), s.getTenantId(), s.getSubdomain(), s.getCustomDomain(),
                s.getHostingProvider(), s.getSiteType(), plaintext, s.maskedKey(),
                s.isActive(), s.isQaApproved(), s.getQaApprovedAt(), s.getSubmittedUrl(),
                s.getCreatedAt());
    }
}
