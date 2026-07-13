package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One credit account per tenant. Holds the tier, the monthly quota,
 * the running counters, and the current billing cycle window.
 *
 * <p><b>Mutation discipline:</b> the atomic decrement paths in
 * {@code CreditService} use native SQL against this table to prevent
 * race conditions (two concurrent sync consumers both seeing
 * "enough available"). Do not add JPA-managed increment helpers to
 * this entity — that pattern would need pessimistic locking to be
 * safe and would fight the native-SQL path.
 *
 * <p>The optimistic {@link Version} is here as a safety net for
 * ad-hoc admin edits (support raises a tenant's quota manually);
 * the service-layer atomic queries don't rely on it.
 */
@Entity
@Table(name = "tenant_credit_accounts")
public class TenantCreditAccount {

    /** Pricing v2 (V67). Ordered lowest → highest. */
    public static final String TIER_FREE = "free";
    public static final String TIER_STUDENT = "student";
    public static final String TIER_STARTER = "starter";
    public static final String TIER_GROWTH = "growth";
    public static final String TIER_PRO = "pro";

    /** Default monthly quotas per tier — mirror of {@code plan_features.credits_month}
     *  from V67. Held here too so a freshly-provisioned account has a
     *  sensible fallback when the caller doesn't specify a plan (e.g. tests). */
    public static final int FREE_MONTHLY_QUOTA    = 100;
    public static final int STUDENT_MONTHLY_QUOTA = 300;
    public static final int STARTER_MONTHLY_QUOTA = 500;
    public static final int GROWTH_MONTHLY_QUOTA  = 3_000;
    public static final int PRO_MONTHLY_QUOTA     = 10_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String tier = TIER_FREE;

    @Column(name = "monthly_quota", nullable = false)
    private int monthlyQuota = FREE_MONTHLY_QUOTA;

    @Column(name = "credits_used", nullable = false)
    private int creditsUsed = 0;

    @Column(name = "topup_credits", nullable = false)
    private int topupCredits = 0;

    @Column(name = "reserved_credits", nullable = false)
    private int reservedCredits = 0;

    @Column(name = "billing_cycle_start", nullable = false)
    private OffsetDateTime billingCycleStart;

    @Column(name = "billing_cycle_end", nullable = false)
    private OffsetDateTime billingCycleEnd;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    protected TenantCreditAccount() {
    }

    /** Provision a fresh Free-tier account for a newly-created tenant.
     *  Retained for existing callers + tests; prefer the tier-aware
     *  overload so new tenants receive the correct budget. */
    public TenantCreditAccount(UUID tenantId, OffsetDateTime now, OffsetDateTime cycleEnd) {
        this(tenantId, now, cycleEnd, TIER_FREE, FREE_MONTHLY_QUOTA);
    }

    /** Provision a fresh account on {@code tier} with {@code monthlyQuota}
     *  credits/cycle. Both must match a valid tier from V67 —
     *  {@code CreditService.provisionAccount(planName)} is the canonical caller. */
    public TenantCreditAccount(UUID tenantId, OffsetDateTime now, OffsetDateTime cycleEnd,
                                String tier, int monthlyQuota) {
        this.tenantId = tenantId;
        this.tier = tier;
        this.monthlyQuota = monthlyQuota;
        this.billingCycleStart = now;
        this.billingCycleEnd = cycleEnd;
    }

    /** Available credits = quota + top-ups minus consumed minus reserved. */
    public int available() {
        return monthlyQuota + topupCredits - creditsUsed - reservedCredits;
    }

    /** Roll the cycle forward one period + reset the monthly-quota counter.
     *  Called lazily on the first consume after cycle end. Top-ups persist
     *  (they were paid for). */
    public void rollCycle(OffsetDateTime now, OffsetDateTime nextCycleEnd) {
        this.billingCycleStart = now;
        this.billingCycleEnd = nextCycleEnd;
        this.creditsUsed = 0;
    }

    public void changeTier(String newTier, int newMonthlyQuota) {
        this.tier = newTier;
        this.monthlyQuota = newMonthlyQuota;
    }

    // ----- accessors --------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getTier() { return tier; }
    public int getMonthlyQuota() { return monthlyQuota; }
    public int getCreditsUsed() { return creditsUsed; }
    public int getTopupCredits() { return topupCredits; }
    public int getReservedCredits() { return reservedCredits; }
    public OffsetDateTime getBillingCycleStart() { return billingCycleStart; }
    public OffsetDateTime getBillingCycleEnd() { return billingCycleEnd; }
}
