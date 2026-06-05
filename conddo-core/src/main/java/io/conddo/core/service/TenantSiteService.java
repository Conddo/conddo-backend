package io.conddo.core.service;

import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.repository.TenantSiteRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant Website Integration (WEBSITE_INTEGRATION_SPEC §1, §2). Owns the
 * API-key lifecycle (generate, rotate, verify), tenant-facing reads, and the
 * public traffic resolver.
 *
 * <p>The plaintext key is the only output of {@link #regenerateKey} that
 * matters — it's never persisted. Storage is bcrypt + last4 (for the masked
 * display the FE shows everywhere except immediately after rotation).
 */
@Service
public class TenantSiteService {

    private static final String KEY_PREFIX = "sk_live_";
    private static final int KEY_BODY_BYTES = 24;   // → 32 base64-url chars

    private final TenantSiteRepository repository;
    private final PasswordHasher passwordHasher;
    private final TenantSession tenantSession;
    private final SecureRandom random = new SecureRandom();

    @PersistenceContext
    private EntityManager entityManager;

    public TenantSiteService(TenantSiteRepository repository, PasswordHasher passwordHasher,
                             TenantSession tenantSession) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.tenantSession = tenantSession;
    }

    // ----- tenant-facing -----------------------------------------------------

    /** Dashboard read. Returns the current site or empty when none exists. */
    @Transactional(readOnly = true)
    public Optional<TenantSite> currentSite() {
        tenantSession.bind();
        return repository.findFirstByOrderByCreatedAtDesc();
    }

    /**
     * Generate the first key for a tenant, OR rotate an existing key (the FE's
     * "Regenerate" button calls the same path). Returns the new plaintext key
     * exactly once — caller (controller) surfaces it in the response and the
     * value is never reconstructible after this method returns.
     */
    @Transactional
    public KeyResult regenerateKey() {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        TenantSite site = repository.findFirstByOrderByCreatedAtDesc()
                .orElseGet(() -> initialiseSite(tenantId));

        String plaintext = newPlaintextKey();
        String hash = passwordHasher.hash(plaintext);
        String last4 = plaintext.substring(plaintext.length() - 4);
        site.rotateKey(hash, last4);
        site = repository.save(site);
        return new KeyResult(site, plaintext);
    }

    // ----- public resolver ---------------------------------------------------

    /**
     * Public traffic resolution. The header API key must bcrypt-match the
     * stored hash, the site must be active + qa_approved. Bypasses RLS — this
     * runs before tenant context is bound. Returns the site only when every
     * check passes; the resolved tenant_id is then bound by the caller for
     * the rest of the request.
     */
    @Transactional(readOnly = true)
    public Optional<TenantSite> resolveBySubdomain(String subdomain, String suppliedKey) {
        if (subdomain == null || subdomain.isBlank() || suppliedKey == null || suppliedKey.isBlank()) {
            return Optional.empty();
        }
        // Carve out the cross-tenant read (V25 policy) — flag scopes to this
        // transaction only. WITH CHECK still demands a bound tenant_id, so
        // this can only widen SELECT, never INSERT/UPDATE.
        entityManager.createNativeQuery("SELECT set_config('app.public_resolver', 'true', true)")
                .getSingleResult();
        Optional<TenantSite> match = repository.findBySubdomain(subdomain.trim().toLowerCase());
        if (match.isEmpty()) {
            return Optional.empty();
        }
        TenantSite site = match.get();
        if (!site.isActive() || !site.isQaApproved()) {
            return Optional.empty();
        }
        // Constant-time within bcrypt's own compare.
        if (!passwordHasher.matches(suppliedKey, site.getApiKeyHash())) {
            return Optional.empty();
        }
        return match;
    }

    // ----- helpers -----------------------------------------------------------

    private TenantSite initialiseSite(UUID tenantId) {
        // Subdomain defaults to the tenant slug — Studio admin can rename
        // later. apiKey hash is a placeholder; the caller immediately
        // calls rotateKey() on the returned entity.
        return repository.save(new TenantSite(tenantId, null, "placeholder", "0000"));
    }

    private String newPlaintextKey() {
        byte[] bytes = new byte[KEY_BODY_BYTES];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record KeyResult(TenantSite site, String plaintextKey) {
    }
}
