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
        return tenantRepository.findAll().stream()
                .sorted(Comparator.comparing(Tenant::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::summarize)
                .toList();
    }

    private TenantSummary summarize(Tenant t) {
        User owner = userRepository.findOwnerByTenantIdCrossTenant(t.getId()).orElse(null);
        long usersCount = userRepository.countByTenantIdCrossTenant(t.getId());
        return new TenantSummary(
                t.getId(), t.getSlug(), t.getName(), t.getVerticalId(), t.getPlanId(),
                t.getStatus(), t.getCreatedAt(),
                owner != null ? owner.getEmail() : null,
                owner != null ? owner.getFullName() : null,
                usersCount);
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
        // Provisioning credit charge is booked inside TenantService.create → CreditService
        // Nothing more to do here.
        String inviteUrl = issueSetPasswordLink(ownerEmail, tenant.getId());
        log.info("Admin provisioned tenant {} ({}), invite URL prepared for {}",
                tenant.getSlug(), tenant.getId(), ownerEmail);
        return new InviteResult(tenant, inviteUrl);
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
            String status, OffsetDateTime createdAt,
            String ownerEmail, String ownerFullName,
            long usersCount) {}

    public record TenantDetail(
            TenantSummary summary,
            User owner,
            long usersCount,
            long ordersCount,
            List<TenantSite> sites,
            TenantCreditAccount credits) {}

    public record InviteResult(Tenant tenant, String inviteUrl) {}
}
