package io.conddo.core.repository;

import io.conddo.core.domain.TenantSocialProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * RLS-scoped — the tenant resolves to its single row via the bound
 * tenant id. The webhook reconciliation path needs to find a profile by
 * the Ayrshare {@code profileKey} (no tenant context yet); that uses the
 * unencrypted key Ayrshare echoes back, see
 * {@link #findByAyrshareProfileKey(String)}.
 */
public interface TenantSocialProfileRepository extends JpaRepository<TenantSocialProfile, UUID> {

    /** Dashboard read — RLS scopes to one row. */
    Optional<TenantSocialProfile> findFirstByOrderByCreatedAtDesc();

    /**
     * Webhook reconcile — looks up by the Ayrshare-side ciphertext stored on
     * the row. Used in a transaction with the {@code app.public_resolver}
     * flag carved out (V25 RLS pattern) so it bypasses tenant scoping safely.
     */
    Optional<TenantSocialProfile> findByAyrshareProfileKey(String encryptedKey);
}
