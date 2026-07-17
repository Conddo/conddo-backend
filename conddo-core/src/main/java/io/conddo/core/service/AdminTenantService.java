package io.conddo.core.service;

import io.conddo.core.auth.OpaqueToken;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.credits.CreditService;
import io.conddo.core.domain.PasswordResetToken;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantCreditAccount;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.domain.User;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.repository.PasswordResetTokenRepository;
import io.conddo.core.repository.TenantCreditAccountRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.TenantSiteRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-tenant admin surface for the Studio dashboard. Every method binds
 * the RLS cross-tenant carve-out via {@link TenantScoped}, so a
 * SUPER_ADMIN can list, inspect, create, deactivate tenants and trigger
 * password resets on their owners without a per-tenant sign-in.
 *
 * <p>All the heavy lifting (create + provision + credit account) delegates
 * to {@link TenantService} — this class is a thin admin-facing facade
 * that adds an invite-token flow so a newly-created tenant's owner can
 * set their own password (admin never sees it).
 */
@Service
public class AdminTenantService {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantService.class);
    /** Invite tokens are longer-lived than a password-reset link — the admin
     *  may sit on the URL before sending it. 7 days matches what most SaaS
     *  onboarding tools use. */
    private static final Duration INVITE_TTL = Duration.ofDays(7);
    /** Fresh-owner random password — used only as filler in the users row;
     *  the owner never sees it and can only sign in after using the invite
     *  URL to set their own. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantService tenantService;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantSiteRepository tenantSiteRepository;
    private final TenantCreditAccountRepository creditAccountRepository;
    private final OrderRepository orderRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordHasher passwordHasher;
    private final NotificationService notificationService;
    private final CreditService creditService;
    private final String appBaseUrl;
    private final Clock clock;

    public AdminTenantService(TenantService tenantService,
                              TenantRepository tenantRepository,
                              UserRepository userRepository,
                              TenantSiteRepository tenantSiteRepository,
                              TenantCreditAccountRepository creditAccountRepository,
                              OrderRepository orderRepository,
                              PasswordResetTokenRepository passwordResetTokenRepository,
                              PasswordHasher passwordHasher,
                              NotificationService notificationService,
                              CreditService creditService,
                              @Value("${conddo.app.base-url:https://app.getconddo.com}") String appBaseUrl,
                              Clock clock) {
        this.tenantService = tenantService;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.tenantSiteRepository = tenantSiteRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.orderRepository = orderRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordHasher = passwordHasher;
        this.notificationService = notificationService;
        this.creditService = creditService;
        this.appBaseUrl = appBaseUrl;
        this.clock = clock;
    }

    // ----- list ------------------------------------------------------------

    /** All tenants with lightweight per-tenant aggregates. Owner + counts
     *  are queried cross-tenant per tenant (N+1); acceptable while the
     *  platform's tenant count is small. Swap to a joined native query
     *  when we cross ~500 tenants. */
    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public List<TenantSummary> listAll() {
        return listAll(false);
    }

    /** {@code includeDeleted = true} returns soft-deleted tenants too —
     *  used by a future "restore" view. Default hides them so the main
     *  panel stays clean. */
    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public List<TenantSummary> listAll(boolean includeDeleted) {
        return tenantRepository.findAll().stream()
                .filter(t -> includeDeleted || !t.isDeleted())
                .sorted(Comparator.comparing(Tenant::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::summarize)
                .toList();
    }

    private TenantSummary summarize(Tenant t) {
        User owner = userRepository.findOwnerByTenantIdCrossTenant(t.getId()).orElse(null);
        long usersCount = userRepository.countByTenantIdCrossTenant(t.getId());
        return new TenantSummary(
                t.getId(), t.getSlug(), t.getName(), t.getVerticalId(), t.getPlanId(),
                t.getStatus(), t.getCreatedAt(), t.getDeletedAt(),
                owner != null ? owner.getEmail() : null,
                owner != null ? owner.getFullName() : null,
                usersCount);
    }

    // ----- attention -------------------------------------------------------

    /**
     * Every tenant that's in a state a support agent should intervene on.
     * Currently detects three failure modes:
     * <ul>
     *   <li><b>NO_SITE</b> — {@link io.conddo.api.signup.TenantActivationListener}
     *       fires site provisioning asynchronously; when AI generation throws
     *       it swallows the error and no {@code tenant_sites} row lands.
     *       The tenant's subdomain 404s until we notice.</li>
     *   <li><b>NO_CREDITS</b> — same async provisioning pattern for
     *       {@code tenant_credit_accounts}. A tenant without a credit account
     *       cannot use any AI feature.</li>
     *   <li><b>OWNER_UNVERIFIED</b> — signup was completed but the owner
     *       never clicked the email-verification link. Some flows (publish,
     *       payments) gate on this.</li>
     * </ul>
     * Aggregated in one pass so the FE panel loads with a single request.
     */
    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public List<AttentionRow> attention() {
        List<Tenant> tenants = tenantRepository.findAll();
        List<AttentionRow> rows = new java.util.ArrayList<>();
        for (Tenant t : tenants) {
            List<String> reasons = new java.util.ArrayList<>();
            List<io.conddo.core.domain.TenantSite> sites =
                    tenantSiteRepository.findByTenantIdCrossTenant(t.getId());
            if (sites.isEmpty()) {
                reasons.add("NO_SITE");
            }
            if (creditAccountRepository.findByTenantId(t.getId()).isEmpty()) {
                reasons.add("NO_CREDITS");
            }
            User owner = userRepository.findOwnerByTenantIdCrossTenant(t.getId()).orElse(null);
            if (owner != null && !owner.isEmailVerified()) {
                reasons.add("OWNER_UNVERIFIED");
            }
            if (!reasons.isEmpty()) {
                rows.add(new AttentionRow(
                        t.getId(), t.getSlug(), t.getName(),
                        t.getVerticalId(), t.getPlanId(),
                        owner != null ? owner.getEmail() : null,
                        reasons, t.getCreatedAt()));
            }
        }
        return rows;
    }

    // ----- detail ----------------------------------------------------------

    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public TenantDetail detail(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new io.conddo.core.common.NotFoundException(
                        "Tenant not found: " + tenantId));
        User owner = userRepository.findOwnerByTenantIdCrossTenant(tenantId).orElse(null);
        long usersCount = userRepository.countByTenantIdCrossTenant(tenantId);
        long ordersCount = orderRepository.countByTenantIdCrossTenant(tenantId);
        List<TenantSite> sites = tenantSiteRepository.findByTenantIdCrossTenant(tenantId);
        Optional<TenantCreditAccount> credits = creditAccountRepository.findByTenantId(tenantId);
        return new TenantDetail(summarize(tenant), owner, usersCount, ordersCount, sites, credits.orElse(null));
    }

    // ----- create ----------------------------------------------------------

    /**
     * Provision a tenant on behalf of a customer. The admin fills in the
     * business identity + owner email; we create the tenant with a random
     * unusable password so the owner can only sign in after using the
     * returned invite URL to set their own.
     *
     * <p>Not annotated with {@link TenantScoped} because {@link TenantService}
     * manages its own transaction and RLS bind sequence — nesting the aspect
     * here would double-bind the cross-tenant GUC and confuse the tx.
     */
    @Transactional
    public InviteResult provisionForCustomer(String businessName, String verticalId, String planId,
                                             String ownerEmail, String ownerFullName) {
        // Delegate to the real create path so a tenant provisioned via admin
        // is indistinguishable from a normal signup (credit account seeded,
        // TenantActivatedEvent fired, initial site row created).
        String throwawayPassword = randomPassword();
        Tenant tenant = tenantService.create(
                businessName, io.conddo.core.common.Slugs.from(businessName),
                verticalId, planId,
                ownerEmail, throwawayPassword, ownerFullName);
        String inviteUrl = issueSetPasswordLink(ownerEmail, tenant.getId());

        // Best-effort auto-send. The controller still returns inviteUrl so
        // the admin has a copy-fallback if the tenant reports "no email".
        boolean emailSent = false;
        try {
            notificationService.sendTenantInvite(
                    ownerEmail,
                    firstNameOf(ownerFullName),
                    businessName,
                    tenant.getSlug(),
                    inviteUrl,
                    (int) INVITE_TTL.toDays());
            emailSent = true;
        } catch (RuntimeException ex) {
            // The sender is already fail-safe (Resend/Brevo adapters log and
            // swallow provider errors), but a template-render or wiring bug
            // could still throw here. Log + fall through — the tenant + row
            // are already saved, and the admin can copy the URL manually.
            log.warn("Tenant invite email send failed for tenant {} ({}): {}",
                    tenant.getSlug(), tenant.getId(), ex.getMessage());
        }
        log.info("Admin provisioned tenant {} ({}), owner={}, emailSent={}",
                tenant.getSlug(), tenant.getId(), ownerEmail, emailSent);
        return new InviteResult(tenant, inviteUrl, emailSent);
    }

    private static String firstNameOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return "there";
        return fullName.trim().split("\\s+")[0];
    }

    // ----- reset password (admin-triggered) --------------------------------

    /**
     * Fires the standard password-reset email flow for the owner of
     * {@code tenantId} — bypasses the FE's slug requirement so a support
     * agent can help a customer who can't remember their workspace.
     */
    @TenantScoped(crossTenant = true)
    @Transactional
    public boolean triggerPasswordReset(UUID tenantId) {
        User owner = userRepository.findOwnerByTenantIdCrossTenant(tenantId).orElse(null);
        if (owner == null) {
            return false;
        }
        String url = issueSetPasswordLink(owner.getEmail(), tenantId);
        notificationService.sendPasswordReset(owner.getEmail(), url.substring(url.indexOf("token=") + 6));
        return true;
    }

    // ----- deactivate ------------------------------------------------------

    /** Soft-deactivate the tenant. Data preserved; sign-in blocked on the
     *  gateway side because {@code Tenant.status} check runs there. */
    @TenantScoped(crossTenant = true)
    @Transactional
    public Tenant deactivate(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new io.conddo.core.common.NotFoundException(
                        "Tenant not found: " + tenantId));
        tenant.deactivate();
        return tenantRepository.save(tenant);
    }

    /**
     * Soft-delete the tenant (V71). Row + all children stay intact for
     * audit + recovery. The tenant is hidden from admin lists (unless
     * {@code includeDeleted}) and sign-in is refused.
     *
     * <p>Caller MUST have verified user intent — the FE requires the
     * admin to type the tenant slug to confirm; the service double-checks
     * because the API endpoint is otherwise easy to hit by mistake.
     *
     * <p>Restore by calling {@link #restore(UUID)} — everything is
     * recoverable.
     */
    @TenantScoped(crossTenant = true)
    @Transactional
    public Tenant softDelete(UUID tenantId, String confirmSlug) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new io.conddo.core.common.NotFoundException(
                        "Tenant not found: " + tenantId));
        if (confirmSlug == null || !confirmSlug.equals(tenant.getSlug())) {
            throw new IllegalArgumentException(
                    "confirmSlug must match the tenant's slug ('" + tenant.getSlug()
                            + "'); got '" + confirmSlug + "'");
        }
        tenant.softDelete(java.time.OffsetDateTime.now());
        Tenant saved = tenantRepository.save(tenant);
        log.info("Tenant {} ({}) soft-deleted by admin", tenant.getSlug(), tenant.getId());
        return saved;
    }

    /** Restore a soft-deleted tenant. Reverses {@link #softDelete}. */
    @TenantScoped(crossTenant = true)
    @Transactional
    public Tenant restore(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new io.conddo.core.common.NotFoundException(
                        "Tenant not found: " + tenantId));
        tenant.restore();
        Tenant saved = tenantRepository.save(tenant);
        log.info("Tenant {} ({}) restored by admin", tenant.getSlug(), tenant.getId());
        return saved;
    }

    // ----- shared helpers --------------------------------------------------

    /** Issues a password-reset token for {@code email} in {@code tenantId}
     *  and returns the URL an admin can paste into an invite email. */
    private String issueSetPasswordLink(String email, UUID tenantId) {
        User owner = userRepository.findOwnerByTenantIdCrossTenant(tenantId).orElse(null);
        if (owner == null) {
            throw new IllegalStateException(
                    "No owner user for tenant " + tenantId + " — cannot issue invite URL");
        }
        String selector = OpaqueToken.randomBase64Url(OpaqueToken.SELECTOR_BYTES);
        String verifier = OpaqueToken.randomBase64Url(OpaqueToken.VERIFIER_BYTES);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(INVITE_TTL);
        passwordResetTokenRepository.save(new PasswordResetToken(
                owner.getId(), tenantId, selector,
                passwordHasher.hash(verifier), expiresAt));
        String rawToken = selector + OpaqueToken.SEPARATOR + verifier;
        return appBaseUrl + "/reset-password?token=" + rawToken;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ----- wire shapes -----------------------------------------------------

    public record TenantSummary(
            UUID id, String slug, String name, String verticalId, String planId,
            String status, OffsetDateTime createdAt, OffsetDateTime deletedAt,
            String ownerEmail, String ownerFullName,
            long usersCount) {}

    public record TenantDetail(
            TenantSummary summary,
            User owner,
            long usersCount,
            long ordersCount,
            List<TenantSite> sites,
            TenantCreditAccount credits) {}

    public record InviteResult(Tenant tenant, String inviteUrl, boolean emailSent) {}

    /** Compact wire shape for the "Needs attention" panel. {@code reasons}
     *  are string codes rather than an enum so the FE can render new codes
     *  even before the FE type is redeployed. */
    public record AttentionRow(
            UUID id, String slug, String name,
            String verticalId, String planId,
            String ownerEmail,
            List<String> reasons,
            java.time.OffsetDateTime createdAt) {}
}
