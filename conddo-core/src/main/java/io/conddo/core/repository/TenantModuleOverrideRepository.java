package io.conddo.core.repository;

import io.conddo.core.domain.TenantModuleOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantModuleOverrideRepository extends JpaRepository<TenantModuleOverride, UUID> {

    /** All overrides for the bound tenant (RLS-scoped). */
    List<TenantModuleOverride> findAll();

    Optional<TenantModuleOverride> findByModuleId(String moduleId);

    /** Cross-tenant lookup for the admin surface. Caller must have set
     *  {@code app.cross_tenant=true} (via {@code @TenantScoped(crossTenant=true)}). */
    @Query(value = "SELECT * FROM tenant_module_overrides WHERE tenant_id = :tenantId",
            nativeQuery = true)
    List<TenantModuleOverride> findByTenantIdCrossTenant(@Param("tenantId") UUID tenantId);

    /** Cross-tenant delete for the admin surface. Used when clearing stale
     *  overrides after a vertical change so the resolver falls back to the
     *  new vertical's defaults. */
    @Modifying
    @Query(value = "DELETE FROM tenant_module_overrides WHERE tenant_id = :tenantId",
            nativeQuery = true)
    int deleteByTenantIdCrossTenant(@Param("tenantId") UUID tenantId);
}
