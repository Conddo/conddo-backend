package io.conddo.api.publicapi;

import io.conddo.core.domain.TenantSite;

import java.util.UUID;

/**
 * The resolved-site context made available to public-site controller methods
 * after {@link PublicSiteInterceptor} has done its work. Carries the
 * tenant_id (for tenant binding) and the {@link TenantSite} row (for module
 * gating against the tenant's plan).
 */
public record PublicSiteAuth(UUID tenantId, String slug, TenantSite site) {

    public static final String REQUEST_ATTRIBUTE = "conddo.publicSiteAuth";
}
