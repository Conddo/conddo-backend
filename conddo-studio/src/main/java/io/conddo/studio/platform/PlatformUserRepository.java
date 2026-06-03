package io.conddo.studio.platform;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    /**
     * Global user search. Empty-string sentinels for unset filters (see
     * {@link PlatformTenantRepository#search} for the Postgres-type reasoning).
     * {@code tenantId} stays nullable since UUID binds to the right SQL type.
     */
    @Query("""
            SELECT u FROM PlatformUser u
            WHERE (:q = '' OR lower(u.email) LIKE lower(concat('%', :q, '%'))
                           OR (u.fullName IS NOT NULL
                               AND lower(u.fullName) LIKE lower(concat('%', :q, '%'))))
              AND (:tenantId IS NULL OR u.tenantId = :tenantId)
              AND (:role = '' OR u.role = :role)
            ORDER BY u.createdAt DESC
            """)
    Page<PlatformUser> search(@Param("q") String q,
                              @Param("tenantId") UUID tenantId,
                              @Param("role") String role,
                              Pageable pageable);

    /** Per-tenant lookup powering the tenant-detail page's nested user list. */
    List<PlatformUser> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Counts used on the tenant detail card (users + active users). */
    long countByTenantId(UUID tenantId);

    long countByTenantIdAndActiveTrue(UUID tenantId);
}
