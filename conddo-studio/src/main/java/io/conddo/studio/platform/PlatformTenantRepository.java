package io.conddo.studio.platform;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PlatformTenantRepository extends JpaRepository<PlatformTenant, UUID> {

    /**
     * Search across every tenant. Empty-string sentinels (not {@code null}) for
     * unset filters — Postgres infers the bind type from the value and would
     * mis-type a JDBC {@code NULL} as {@code bytea}, breaking the lower() call.
     * The service layer normalises null/blank inputs to {@code ""} before calling.
     */
    @Query("""
            SELECT t FROM PlatformTenant t
            WHERE (:q = '' OR lower(t.name) LIKE lower(concat('%', :q, '%'))
                           OR lower(t.slug) LIKE lower(concat('%', :q, '%'))
                           OR (t.customDomain IS NOT NULL
                               AND lower(t.customDomain) LIKE lower(concat('%', :q, '%'))))
              AND (:status = '' OR t.status = :status)
            ORDER BY t.createdAt DESC
            """)
    Page<PlatformTenant> search(@Param("q") String q,
                                @Param("status") String status,
                                Pageable pageable);
}
