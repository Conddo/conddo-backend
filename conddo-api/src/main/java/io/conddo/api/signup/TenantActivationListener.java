package io.conddo.api.signup;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.BillingService;
import io.conddo.core.service.TenantSiteService;
import io.conddo.core.service.WebsiteGenerationService;
import io.conddo.core.signup.TenantActivatedEvent;
import io.conddo.core.signup.WebsiteTypeRecommendation;
import io.conddo.core.signup.WebsiteTypeResolver;
import io.conddo.core.studio.StudioJobGateway;
import io.conddo.core.tenant.TenantScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Closes the signup → Studio loop: after a tenant commits, resolve which kind of
 * website they need ({@link WebsiteTypeResolver}) and hand a {@code WEBSITE_BUILD}
 * job to Studio via the existing {@link StudioJobGateway} intake seam.
 *
 * <p>Runs <b>after commit</b> so a flaky Studio call never rolls back signup. The
 * gateway is already fail-safe (returns empty on errors / unconfigured), but we
 * also catch everything else here — signup must never break because of Studio.
 */
@Component
public class TenantActivationListener {

    private static final Logger log = LoggerFactory.getLogger(TenantActivationListener.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final WebsiteTypeResolver websiteTypeResolver;
    private final StudioJobGateway studioJobGateway;
    private final PaymentsGateway paymentsGateway;
    private final BillingService billingService;
    private final WebsiteGenerationService websiteGeneration;
    private final TenantSiteService tenantSiteService;
    private final NotificationService notificationService;

    public TenantActivationListener(TenantRepository tenantRepository,
                                    UserRepository userRepository,
                                    WebsiteTypeResolver websiteTypeResolver,
                                    StudioJobGateway studioJobGateway,
                                    PaymentsGateway paymentsGateway,
                                    BillingService billingService,
                                    WebsiteGenerationService websiteGeneration,
                                    TenantSiteService tenantSiteService,
                                    NotificationService notificationService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.websiteTypeResolver = websiteTypeResolver;
        this.studioJobGateway = studioJobGateway;
        this.paymentsGateway = paymentsGateway;
        this.billingService = billingService;
        this.websiteGeneration = websiteGeneration;
        this.tenantSiteService = tenantSiteService;
        this.notificationService = notificationService;
    }

    /**
     * Fifth handler on the same event: email the platform admin so we know
     * within seconds when a new tenant signs up. No-op when
     * {@code conddo.notify.platform-admin-email} is unset. Cross-tenant so
     * the owner-user lookup works — the event handler doesn't have a bound
     * tenant context.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TenantScoped(crossTenant = true)
    public void onTenantActivated_notifyPlatformAdmin(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            User owner = userRepository.findOwnerByTenantIdCrossTenant(tenant.getId()).orElse(null);
            notificationService.sendPlatformSignupAlert(
                    tenant.getName(),
                    tenant.getVerticalId(),
                    tenant.getPlanId(),
                    owner != null ? owner.getEmail() : tenant.getContactEmail(),
                    owner != null ? owner.getFullName() : null,
                    tenant.getSlug());
        } catch (RuntimeException ex) {
            log.error("Platform-admin signup notification failed for tenant {}: {}",
                    event.tenantId(), ex.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onTenantActivated(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));

            WebsiteTypeRecommendation rec = websiteTypeResolver.resolve(
                    tenant.getVerticalId(), tenant.getPlanId());

            Map<String, Object> brief = briefFor(tenant, rec);
            String title = "Website Build — " + tenant.getName();
            studioJobGateway.createJob(tenant.getId(), "WEBSITE_BUILD", title, brief)
                    .ifPresent(ref -> log.info("Auto-created Studio job {} ({}) for tenant {} — {}",
                            ref.jobNumber(), rec.type(), tenant.getId(), rec.reasoning()));
        } catch (RuntimeException ex) {
            // Signup is already committed — never let a downstream failure surface.
            log.error("Auto-create Studio job failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    /**
     * Second handler on the same event (§7a): provision the tenant's RoutePay
     * sub-account through conddo-payments. Separate listener method so a Studio
     * outage doesn't suppress payments provisioning and vice versa — each
     * gateway is independently fail-safe.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onTenantActivated_provisionPayments(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            String contactEmail = tenant.getContactEmail() == null
                    ? "owner+" + tenant.getSlug() + "@conddo.io"
                    : tenant.getContactEmail();
            paymentsGateway.provisionTenantAccount(tenant.getId(), tenant.getSlug(),
                            tenant.getName(), contactEmail)
                    .ifPresent(account -> log.info("Provisioned payments sub-account for tenant {} — {} ({})",
                            tenant.getId(), account.subaccountId(), account.status()));
        } catch (RuntimeException ex) {
            log.error("Auto-provision payments failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    /**
     * Third handler on the same event (BILLING_TIERS_SPEC §3): start the
     * 14-day trial for the new tenant. Idempotent — BillingService skips if a
     * live subscription already exists, so a replayed event is safe.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTenantActivated_startTrial(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            // tenant.getPlanId() is now post-rename — launcher / growth / scaler.
            // Fall through to launcher (BillingService default) when the wizard didn't pick.
            var sub = billingService.createTrialForNewTenant(tenant.getId(), tenant.getPlanId());
            log.info("Started {}-trial for tenant {} (plan={}, expires={})",
                    sub.getStatus(), tenant.getId(), tenant.getPlanId(), sub.getExpiresAt());
        } catch (RuntimeException ex) {
            log.error("Auto-start trial failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    /**
     * Fourth handler on the same event: seed the managed website — call the
     * AI generator with the tenant's name + vertical + vibe, save as a draft
     * on tenant_sites. Free (bundled with the AI provisioning charge that's
     * already been booked at tenant creation). Fully fail-safe: an LLM
     * outage falls through the generator's rule-based stub, and a save
     * failure just logs — the tenant can still complete signup.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTenantActivated_generateWebsite(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            WebsiteGenerationService.Generated generated = websiteGeneration.generate(
                    tenant.getName(), tenant.getVerticalId(), tenant.getWebsiteVibe());
            tenantSiteService.provisionManagedSite(
                    tenant.getId(), tenant.getSlug(),
                    generated.sections(), generated.theme());
            log.info("Managed site draft seeded for tenant {} (slug={}, sections={})",
                    tenant.getId(), tenant.getSlug(), generated.sections().keySet());
        } catch (RuntimeException ex) {
            log.error("Auto-seed managed site failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    private Map<String, Object> briefFor(Tenant tenant, WebsiteTypeRecommendation rec) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("source", "tenant-activated");
        brief.put("tenantId", tenant.getId().toString());
        brief.put("tenantSlug", tenant.getSlug());
        brief.put("businessName", tenant.getName());
        brief.put("vertical", tenant.getVerticalId());
        brief.put("plan", tenant.getPlanId());
        brief.put("websiteType", rec.type().name());
        brief.put("recommendedSections", rec.sections());
        brief.put("typeReasoning", rec.reasoning());
        if (tenant.getContactEmail() != null) {
            brief.put("contactEmail", tenant.getContactEmail());
        }
        if (tenant.getContactPhone() != null) {
            brief.put("contactPhone", tenant.getContactPhone());
        }
        return brief;
    }
}
