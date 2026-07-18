package io.conddo.core.repository;

import io.conddo.core.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lookups are by the unguessable {@code selector} (never enumerated) or by
 * {@code familyId} for reuse-driven family revocation. Not RLS-scoped — these
 * run on unauthenticated refresh requests (see V4__auth_grants.sql).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findBySelector(String selector);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserId(UUID userId);

    /** Admin session-reset: nukes every refresh token for every user of the
     *  given tenant. Native + cross-tenant because refresh_tokens is not
     *  RLS-scoped (tokens are looked up on unauthenticated requests). */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE user_id IN "
            + "(SELECT id FROM users WHERE tenant_id = :tenantId)",
            nativeQuery = true)
    int deleteAllForTenant(@Param("tenantId") UUID tenantId);
}
