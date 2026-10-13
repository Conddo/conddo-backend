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
import io.conddo.core.tenant.TenantScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes the signup loop: after a tenant commits, provision its payments
 * sub-account, start its trial, seed its managed website, and alert the
 * platform admin.
 *
 * <p>Runs <b>after commit</b> so a flaky downstream call never rolls back
 * signup. Each handler is independently fail-safe — signup must never break
 * because of a downstream dependency.
 */
@Component
public class TenantActivationListener {

    private static final Logger log = LoggerFactory.getLogger(TenantActivationListener.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PaymentsGateway paymentsGateway;
    private final BillingService billingService;
    private final WebsiteGenerationService websiteGeneration;
    private final TenantSiteService tenantSiteService;
    private final NotificationService notificationService;

    public TenantActivationListener(TenantRepository tenantRepository,
                                    UserRepository userRepository,
                                    PaymentsGateway paymentsGateway,
                                    BillingService billingService,
                                    WebsiteGenerationService websiteGeneration,
                                    TenantSiteService tenantSiteService,
                                    NotificationService notificationService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.paymentsGateway = paymentsGateway;
        this.billingService = billingService;
        this.websiteGeneration = websiteGeneration;
        this.tenantSiteService = tenantSiteService;
        this.notificationService = notificationService;
    }

    /**
     * Email the platform admin so we know within seconds when a new tenant
     * signs up. No-op when {@code conddo.notify.platform-admin-email} is unset.
     * Cross-tenant so the owner-user lookup works — the event handler doesn't
     * have a bound tenant context.
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

    /**
     * Provision the tenant's RoutePay sub-account through conddo-payments
     * (ACTION_LIST §7a). Independently fail-safe.
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
     * Start the 14-day trial for the new tenant (BILLING_TIERS_SPEC §3).
     * Idempotent — BillingService skips if a live subscription already exists,
     * so a replayed event is safe.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTenantActivated_startTrial(TenantActivatedEvent event) {
        try {
            Tenant tenant = tenantRepository.findById(event.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant " + event.tenantId() + " vanished"));
            var sub = billingService.createTrialForNewTenant(tenant.getId(), tenant.getPlanId());
            log.info("Started {}-trial for tenant {} (plan={}, expires={})",
                    sub.getStatus(), tenant.getId(), tenant.getPlanId(), sub.getExpiresAt());
        } catch (RuntimeException ex) {
            log.error("Auto-start trial failed for tenant {}: {}", event.tenantId(), ex.getMessage());
        }
    }

    /**
     * Seed the managed website — call the AI generator with the tenant's name +
     * vertical + vibe, save as a draft on tenant_sites. Fully fail-safe: an LLM
     * outage falls through the generator's rule-based stub, and a save failure
     * just logs — the tenant can still complete signup.
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
}
