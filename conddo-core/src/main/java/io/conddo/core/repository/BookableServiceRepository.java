package io.conddo.core.repository;

import io.conddo.core.domain.BookableService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped bookable services. RLS enforces the tenant filter; the
 * cross-tenant carve-out plus a native query below powers the public
 * page's service list without a bound tenant JWT.
 */
public interface BookableServiceRepository extends JpaRepository<BookableService, UUID> {

    /** All rows for the bound tenant, ordered as the FE displays them. */
    List<BookableService> findAllByOrderBySortOrderAscNameAsc();

    /** Only active rows — customer-facing list on the public booking page. */
    List<BookableService> findByActiveTrueOrderBySortOrderAscNameAsc();

    /** Public resolver lookup — the tenant isn't RLS-bound (public page
     *  path), so this uses the {@code app.public_resolver} carve-out that
     *  the policy accepts. Callers MUST have set that GUC first. */
    @Query(value = """
            SELECT * FROM bookable_services
             WHERE tenant_id = :tenantId AND active = TRUE
             ORDER BY sort_order ASC, name ASC
            """, nativeQuery = true)
    List<BookableService> findActiveByTenantIdForPublic(@Param("tenantId") UUID tenantId);
}
