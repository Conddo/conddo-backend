package io.conddo.core.repository;

import io.conddo.core.domain.TenantRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * All queries here are RLS-scoped to the bound tenant (tenant-side reads),
 * with two exceptions gated by {@code app.cross_tenant=true} that support
 * the admin surface.
 */
public interface TenantRequestRepository extends JpaRepository<TenantRequest, UUID> {

    /** Tenant-side list — newest first. RLS scopes to the bound tenant. */
    List<TenantRequest> findAllByOrderByCreatedAtDesc();

    /** Admin-side list, filtered by status, newest first. Caller MUST have
     *  bound {@code app.cross_tenant=true} (i.e. run under
     *  {@code @TenantScoped(crossTenant = true)}). */
    @Query(value = "SELECT * FROM tenant_requests WHERE status = :status "
            + "ORDER BY created_at DESC", nativeQuery = true)
    List<TenantRequest> findByStatusCrossTenant(@Param("status") String status);

    /** Admin-side list, everything, newest first. Same cross-tenant precondition. */
    @Query(value = "SELECT * FROM tenant_requests ORDER BY created_at DESC",
            nativeQuery = true)
    List<TenantRequest> findAllCrossTenant();

    /** Admin-side counts by status — used by the studio dashboard summary. */
    @Query(value = "SELECT status AS status, COUNT(*) AS count "
            + "FROM tenant_requests GROUP BY status", nativeQuery = true)
    List<StatusCount> countByStatusCrossTenant();

    interface StatusCount {
        String getStatus();
        long getCount();
    }
}
