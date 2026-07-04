package io.conddo.core.service;

import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.SubdomainRules;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.TenantSiteRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant Website Integration (WEBSITE_INTEGRATION_SPEC §1, §2). Owns the
 * API-key lifecycle (generate, rotate, verify), the tenant-facing
 * read/claim/submit flow, the public traffic resolver, and the admin
 * QA-approval pipeline.
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
    private final TenantRepository tenantRepository;
    private final PasswordHasher passwordHasher;
    private final TenantSession tenantSession;
    private final SecureRandom random = new SecureRandom();

    @PersistenceContext
    private EntityManager entityManager;

    public TenantSiteService(TenantSiteRepository repository,
                             TenantRepository tenantRepository,
                             PasswordHasher passwordHasher,
                             TenantSession tenantSession) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
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
     * Provision a managed website (Path A) for a freshly-created tenant with
     * the AI-generated first draft. Caller is the async
     * {@code TenantActivationListener}; runs cross-tenant since the async
     * transaction runs without a bound context. Idempotent — no-op if the
     * tenant already has a managed row.
     */
    @Transactional
    public TenantSite provisionManagedSite(UUID tenantId, String subdomain,
                                           java.util.Map<String, Object> draftSections,
                                           java.util.Map<String, Object> draftTheme) {
        tenantSession.bindCrossTenant();
        try {
            java.util.List<TenantSite> existing = repository.findAllByOrderByCreatedAtDesc();
            for (TenantSite site : existing) {
                if (tenantId.equals(site.getTenantId()) && site.isManaged()) {
                    return site;
                }
            }
            String normalised = SubdomainRules.normalise(subdomain);
            if (!SubdomainRules.isValid(normalised)) {
                // Pathological — the caller sends the tenant slug, which was
                // validated at signup. Fall back to a random suffix so we
                // never block the async flow.
                normalised = "site-" + tenantId.toString().substring(0, 8);
            }
            TenantSite site = TenantSite.managed(tenantId, normalised, draftSections, draftTheme);
            return repository.save(site);
        } finally {
            tenantSession.clearCrossTenant();
        }
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

    /**
     * Tenant claims (or renames) their subdomain. Validates RFC-1035 + the
     * shared reserved list ({@link SubdomainRules}) before persist; surfaces
     * the unique-index collision as {@link SubdomainTakenException} so the FE
     * can render a clean "already taken" message instead of a 500.
     */
    @Transactional
    public TenantSite updateSubdomain(String newSubdomain) {
        tenantSession.bind();
        String normalised = SubdomainRules.normalise(newSubdomain);
        if (!SubdomainRules.isValid(normalised)) {
            throw new InvalidSubdomainException(
                    "Subdomain must be 3–63 lowercase letters/digits/hyphens, not a reserved label.");
        }
        TenantSite site = repository.findFirstByOrderByCreatedAtDesc()
                .orElseGet(() -> initialiseSite(TenantContext.require()));
        site.setSubdomain(normalised);
        try {
            return repository.saveAndFlush(site);
        } catch (DataIntegrityViolationException ex) {
            throw new SubdomainTakenException(
                    "Subdomain '" + normalised + "' is already taken — try a different one.");
        }
    }

    /**
     * Tenant submits a built URL for QA review. The url itself is stored on
     * the site row; activation still requires {@link #approve} from a STAFF /
     * SUPER_ADMIN. Re-submission is allowed (just overwrites the URL).
     */
    @Transactional
    public TenantSite submitForReview(String submittedUrl) {
        tenantSession.bind();
        if (submittedUrl == null || submittedUrl.isBlank()) {
            throw new InvalidSubmittedUrlException("submittedUrl cannot be blank");
        }
        String trimmed = submittedUrl.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            throw new InvalidSubmittedUrlException(
                    "submittedUrl must start with http:// or https://");
        }
        TenantSite site = repository.findFirstByOrderByCreatedAtDesc()
                .orElseGet(() -> initialiseSite(TenantContext.require()));
        site.setSubmittedUrl(trimmed);
        return repository.save(site);
    }

    // ----- public resolver ---------------------------------------------------

    /**
     * Public managed-site resolver — for the {@code <slug>.getconddo.com}
     * renderer. No API key: sites are public HTML. Uses the same RLS
     * carve-out as {@link #resolveBySubdomain} but scoped to published
     * managed rows via the V60 partial indexes.
     *
     * <p>Host may be a subdomain of the platform ({@code shop.getconddo.com})
     * or a fully-verified custom domain ({@code amakastore.com}). Returns
     * the matching site or empty.
     */
    @Transactional(readOnly = true)
    public Optional<TenantSite> resolvePublicHost(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String normalized = host.trim().toLowerCase();
        // Strip an optional :port and www. prefix so DNS quirks don't dead-end.
        int colon = normalized.indexOf(':');
        if (colon > 0) normalized = normalized.substring(0, colon);
        if (normalized.startsWith("www.")) normalized = normalized.substring(4);

        entityManager.createNativeQuery("SELECT set_config('app.public_resolver', 'true', true)")
                .getSingleResult();

        // Try subdomain first — the most common path for managed sites.
        String platformDomain = ".getconddo.com";
        if (normalized.endsWith(platformDomain)) {
            String slug = normalized.substring(0, normalized.length() - platformDomain.length());
            return repository.findPublishedManagedBySubdomain(slug);
        }
        // Fall back to custom-domain match.
        return repository.findPublishedByCustomDomain(normalized);
    }

    /**
     * Public traffic resolution. The header API key must bcrypt-match the
     * stored hash, the site must be active + qa_approved. Bypasses RLS via
     * the V25 {@code app.public_resolver} carve-out — this runs before tenant
     * context is bound. Returns the site only when every check passes; the
     * resolved tenant_id is then bound by the caller for the rest of the
     * request.
     */
    @Transactional(readOnly = true)
    public Optional<TenantSite> resolveBySubdomain(String subdomain, String suppliedKey) {
        if (subdomain == null || subdomain.isBlank() || suppliedKey == null || suppliedKey.isBlank()) {
            return Optional.empty();
        }
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
        if (!passwordHasher.matches(suppliedKey, site.getApiKeyHash())) {
            return Optional.empty();
        }
        return match;
    }

    // ----- staff / admin -----------------------------------------------------

    /** QA queue for STAFF / SUPER_ADMIN. Cross-tenant by design. */
    @Transactional(readOnly = true)
    public List<TenantSite> listForReview(SiteFilter filter) {
        tenantSession.bindCrossTenant();
        return switch (filter) {
            case PENDING -> repository.findByQaApprovedFalseOrderByCreatedAtDesc();
            case APPROVED -> repository.findByQaApprovedTrueOrderByCreatedAtDesc();
            case ACTIVE -> repository.findByActiveTrueOrderByCreatedAtDesc();
            case ALL -> repository.findAllByOrderByCreatedAtDesc();
        };
    }

    /**
     * Admin approval flow. Marks {@code qa_approved} + {@code is_active} and
     * stamps the reviewer/timestamp. Idempotent: re-approving an already-live
     * site just re-stamps the metadata.
     */
    @Transactional
    public TenantSite approve(UUID siteId, UUID staffId) {
        tenantSession.bindCrossTenant();
        TenantSite site = repository.findById(siteId)
                .orElseThrow(() -> new NotFoundException("Site not found: " + siteId));
        if (site.getSubdomain() == null || site.getSubdomain().isBlank()) {
            throw new InvalidSubdomainException(
                    "Cannot approve — tenant has not claimed a subdomain yet.");
        }
        site.approveQa(staffId, OffsetDateTime.now());
        site.activate();
        return repository.save(site);
    }

    /** Admin take-down. Flips is_active=false; does not undo qa_approved. */
    @Transactional
    public TenantSite deactivate(UUID siteId) {
        tenantSession.bindCrossTenant();
        TenantSite site = repository.findById(siteId)
                .orElseThrow(() -> new NotFoundException("Site not found: " + siteId));
        site.deactivate();
        return repository.save(site);
    }

    // ----- helpers -----------------------------------------------------------

    /**
     * First-time site initialisation. Seeds the subdomain to the tenant slug
     * when the slug is RFC-1035 valid and not already taken — most merchants
     * never need to claim explicitly. Placeholder hash is overwritten by the
     * caller's {@code rotateKey} on the same row.
     */
    private TenantSite initialiseSite(UUID tenantId) {
        String defaultSubdomain = defaultSubdomainFor(tenantId);
        return repository.save(new TenantSite(tenantId, defaultSubdomain, "placeholder", "0000"));
    }

    private String defaultSubdomainFor(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return null;
        }
        String candidate = SubdomainRules.normalise(tenant.getSlug());
        if (!SubdomainRules.isValid(candidate)) {
            return null;
        }
        // Cross-tenant SELECT — we're checking whether anyone else already
        // owns this subdomain. The check is by-subdomain not by-tenant, so
        // the existing public-resolver carve-out is the right tool.
        entityManager.createNativeQuery("SELECT set_config('app.public_resolver', 'true', true)")
                .getSingleResult();
        if (repository.findBySubdomain(candidate).isPresent()) {
            return null;
        }
        return candidate;
    }

    private String newPlaintextKey() {
        byte[] bytes = new byte[KEY_BODY_BYTES];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record KeyResult(TenantSite site, String plaintextKey) {
    }

    public enum SiteFilter {
        PENDING, APPROVED, ACTIVE, ALL
    }

    public static class InvalidSubdomainException extends RuntimeException {
        public InvalidSubdomainException(String msg) {
            super(msg);
        }
    }

    public static class SubdomainTakenException extends RuntimeException {
        public SubdomainTakenException(String msg) {
            super(msg);
        }
    }

    public static class InvalidSubmittedUrlException extends RuntimeException {
        public InvalidSubmittedUrlException(String msg) {
            super(msg);
        }
    }
}
