package io.conddo.core.repository;

import io.conddo.core.domain.TenantSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped via RLS for the dashboard read path. The public traffic path
 * runs with full DB privileges (binds tenant_id from the resolved subdomain),
 * so a separate dedicated query reads by subdomain bypassing RLS — see
 * TenantSiteService.resolveBySubdomain.
 */
public interface TenantSiteRepository extends JpaRepository<TenantSite, UUID> {

    /** Dashboard read — RLS scopes this to the bound tenant. */
    Optional<TenantSite> findFirstByOrderByCreatedAtDesc();

    /** Public traffic resolver — used inside a non-tenant-bound query. */
    Optional<TenantSite> findBySubdomain(String subdomain);

    /** Public managed-site resolver — matches only sites that have been
     *  published at least once. Uses the partial index added in V60. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT * FROM tenant_sites
             WHERE managed = TRUE
               AND published_at IS NOT NULL
               AND subdomain = :subdomain
             LIMIT 1
            """, nativeQuery = true)
    Optional<TenantSite> findPublishedManagedBySubdomain(
            @org.springframework.data.repository.query.Param("subdomain") String subdomain);

    /** Custom-domain resolver — for tenants who have hooked up their own
     *  domain via a CNAME. Only matches published rows. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT * FROM tenant_sites
             WHERE published_at IS NOT NULL
               AND custom_domain = :customDomain
             LIMIT 1
            """, nativeQuery = true)
    Optional<TenantSite> findPublishedByCustomDomain(
            @org.springframework.data.repository.query.Param("customDomain") String customDomain);

    // ----- staff / admin (cross-tenant; needs app.cross_tenant=true) ---------

    java.util.List<TenantSite> findByQaApprovedFalseOrderByCreatedAtDesc();

    java.util.List<TenantSite> findByQaApprovedTrueOrderByCreatedAtDesc();

    java.util.List<TenantSite> findByActiveTrueOrderByCreatedAtDesc();

    java.util.List<TenantSite> findAllByOrderByCreatedAtDesc();
}
