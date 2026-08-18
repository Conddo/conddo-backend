package io.conddo.core.repository;

import io.conddo.core.domain.TenantIntegration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Connected payment accounts. Tenant-scoped via RLS — the tenant id on
 * {@code findByTenantIdAndProvider} is belt-and-braces; the poller uses
 * the provider-scoped queries under {@code app.cross_tenant=true}.
 */
public interface TenantIntegrationRepository extends JpaRepository<TenantIntegration, UUID> {

    /** Tenant's connected accounts, newest first. RLS-scoped. */
    List<TenantIntegration> findAllByOrderByCreatedAtDesc();

    Optional<TenantIntegration> findByTenantIdAndProvider(UUID tenantId, String provider);

    /** One row per (tenant, provider) — cross-tenant for the poller. */
    List<TenantIntegration> findByProviderAndStatus(String provider, String status);

    /** Poller view: connected + errored accounts (so a transient failure
     *  doesn't permanently drop the account from the sync loop). */
    List<TenantIntegration> findByProviderAndStatusIn(String provider,
                                                      java.util.Collection<String> statuses);

    /** Webhook tenant-resolution — cross-tenant lookup on the
     *  non-secret merchant reference. Caller MUST bind
     *  {@code app.cross_tenant} first. */
    Optional<TenantIntegration> findByProviderAndMerchantReference(String provider, String merchantReference);
}
