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
}
