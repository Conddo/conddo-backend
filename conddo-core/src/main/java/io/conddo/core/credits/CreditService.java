package io.conddo.core.credits;

import io.conddo.core.domain.CreditTransaction;
import io.conddo.core.domain.TenantCreditAccount;
import io.conddo.core.repository.CreditTransactionRepository;
import io.conddo.core.repository.TenantCreditAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The one entry-point every credit-consuming code path goes through.
 *
 * <p>Two flavors:
 * <ul>
 *   <li><b>Sync</b> — {@link #consume}: decrement the counter atomically,
 *       audit the transaction, done. Use for actions that either succeed
 *       or don't happen at all (order processed, marketing message sent).</li>
 *   <li><b>Async</b> — {@link #reserve} then {@link #confirm} or
 *       {@link #release}: earmark credits before firing a long-running
 *       action, then either bank them (success) or hand them back (fail).
 *       Reservations carry a TTL so a crashed worker doesn't lose credits
 *       forever.</li>
 * </ul>
 *
 * <p><b>Race safety:</b> both {@code consume} and {@code reserve} use a
 * single UPDATE that combines availability check + counter mutation. Two
 * concurrent callers cannot both succeed against a shared pool — the
 * losing UPDATE affects 0 rows and the caller sees
 * {@link CreditExhaustedException}. Rollover is handled BEFORE the
 * decrement, so a caller landing on the first tick of a new cycle gets
 * a fresh 100 credits without a cron running.
 */
@Service
public class CreditService {

    /** How long a reservation stays live before it's considered stale.
     *  Long-running LLM calls typically finish inside 60s; giving them
     *  5 minutes leaves lots of headroom without letting a stuck worker
     *  hoard credits indefinitely. */
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(5);

    /** Default billing cycle length for the Free tier + Starter/Growth
     *  outside of a Paystack subscription (which drives its own cycle
     *  boundaries via webhook). */
    public static final Duration CYCLE_LENGTH = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final TenantCreditAccountRepository accounts;
    private final CreditTransactionRepository transactions;
    private final Clock clock;

    public CreditService(TenantCreditAccountRepository accounts,
                         CreditTransactionRepository transactions,
                         Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ----- Sync path --------------------------------------------------------

    /**
     * Sync consume — decrement credits atomically. Throws
     * {@link CreditExhaustedException} when the tenant is out. The
     * caller must have a bound tenant context (RLS).
     */
    @Transactional
    public CreditTransaction consume(UUID tenantId, String actionType,
                                     UUID referenceId, String referenceType) {
        int cost = CreditActions.costOf(actionType);
        rollCycleIfExpired(tenantId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        int affected = accounts.consumeIfAvailable(tenantId, cost, now);
        if (affected == 0) {
            throw new CreditExhaustedException(tenantId, actionType, cost, availableSnapshot(tenantId));
        }
        flagRunawayIfNecessary(tenantId, now);
        return transactions.save(CreditTransaction.consumed(
                tenantId, actionType, cost, referenceId, referenceType, now));
    }

    /** Warns loudly (log) when a tenant burns through their full free quota
     *  within 24 hours of signup. Legitimate SMBs don't process 100+ credit
     *  actions on their first day; this is our smoke alarm for scripted
     *  free-tier abuse. Ops can wire this to a paging system later. */
    private void flagRunawayIfNecessary(UUID tenantId, OffsetDateTime now) {
        TenantCreditAccount acc = accounts.findByTenantId(tenantId).orElse(null);
        if (acc == null) return;
        Duration age = Duration.between(acc.getBillingCycleStart(), now);
        if (age.toHours() < 24 && acc.getCreditsUsed() >= acc.getMonthlyQuota()) {
            log.warn("RUNAWAY-FREE-TIER: tenant {} spent {}/{} credits within {}h of signup",
                    tenantId, acc.getCreditsUsed(), acc.getMonthlyQuota(), age.toHours());
        }
    }

    // ----- Async path -------------------------------------------------------

    /**
     * Reserve credits before firing an action that may take a while
     * (LLM call, workflow trigger, website generation). The returned
     * transaction id is what {@link #confirm} / {@link #release} keys off.
     */
    @Transactional
    public CreditTransaction reserve(UUID tenantId, String actionType,
                                     UUID referenceId, String referenceType) {
        int cost = CreditActions.costOf(actionType);
        rollCycleIfExpired(tenantId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        int affected = accounts.reserveIfAvailable(tenantId, cost, now);
        if (affected == 0) {
            throw new CreditExhaustedException(tenantId, actionType, cost, availableSnapshot(tenantId));
        }
        return transactions.save(CreditTransaction.reserved(
                tenantId, actionType, cost, referenceId, referenceType, now.plus(RESERVATION_TTL)));
    }

    /**
     * Confirm a live reservation — move it from reserved to consumed.
     * Idempotent: calling confirm twice on the same reservation is a
     * no-op. Throws {@link IllegalArgumentException} if the reservation
     * doesn't belong to the tenant.
     */
    @Transactional
    public void confirm(UUID tenantId, UUID transactionId) {
        CreditTransaction tx = transactions.findByIdAndTenantId(transactionId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No such reservation: " + transactionId));
        if (!CreditTransaction.STATUS_RESERVED.equals(tx.getStatus())) {
            return; // already confirmed or released
        }
        int affected = accounts.confirmReservation(tenantId, tx.getCreditsConsumed());
        if (affected == 0) {
            // Shouldn't happen — reservation exists but counters don't add up.
            log.error("Confirm reservation {} on tenant {} affected 0 rows", transactionId, tenantId);
        }
        tx.confirm(OffsetDateTime.now(clock));
        transactions.save(tx);
    }

    /**
     * Release a live reservation — return the credits to the available
     * pool. Also idempotent.
     */
    @Transactional
    public void release(UUID tenantId, UUID transactionId) {
        CreditTransaction tx = transactions.findByIdAndTenantId(transactionId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No such reservation: " + transactionId));
        if (!CreditTransaction.STATUS_RESERVED.equals(tx.getStatus())) {
            return;
        }
        int affected = accounts.releaseReservation(tenantId, tx.getCreditsConsumed());
        if (affected == 0) {
            log.error("Release reservation {} on tenant {} affected 0 rows", transactionId, tenantId);
        }
        tx.release(OffsetDateTime.now(clock));
        transactions.save(tx);
    }

    // ----- Read helpers -----------------------------------------------------

    /** Snapshot of current available credits, after lazy rollover. */
    @Transactional
    public int available(UUID tenantId) {
        rollCycleIfExpired(tenantId);
        return availableSnapshot(tenantId);
    }

    /** Full dashboard summary — post-rollover snapshot plus per-action
     *  breakdown of the current cycle's CONSUMED transactions. Feeds
     *  {@code GET /api/v1/me/credits}. */
    @Transactional
    public Summary summaryFor(UUID tenantId) {
        rollCycleIfExpired(tenantId);
        TenantCreditAccount account = accounts.findByTenantId(tenantId).orElse(null);
        if (account == null) {
            return Summary.empty();
        }
        List<Breakdown> breakdown = transactions
                .findTop50ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(tx -> CreditTransaction.STATUS_CONSUMED.equals(tx.getStatus()))
                .filter(tx -> tx.getCreatedAt() != null
                        && !tx.getCreatedAt().isBefore(account.getBillingCycleStart()))
                .collect(java.util.stream.Collectors.groupingBy(
                        CreditTransaction::getActionType,
                        java.util.stream.Collectors.summingInt(CreditTransaction::getCreditsConsumed)))
                .entrySet().stream()
                .map(e -> new Breakdown(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingInt(Breakdown::credits).reversed())
                .toList();
        return new Summary(
                account.getTier(),
                account.getMonthlyQuota(),
                account.getCreditsUsed(),
                account.getTopupCredits(),
                account.getReservedCredits(),
                account.available(),
                account.getBillingCycleStart(),
                account.getBillingCycleEnd(),
                breakdown);
    }

    private int availableSnapshot(UUID tenantId) {
        return accounts.findByTenantId(tenantId)
                .map(TenantCreditAccount::available)
                .orElse(0);
    }

    /** Dashboard-facing view of the tenant's credit account. */
    public record Summary(
            String tier,
            int monthlyQuota,
            int creditsUsed,
            int topupCredits,
            int reservedCredits,
            int available,
            OffsetDateTime cycleStart,
            OffsetDateTime cycleEnd,
            java.util.List<Breakdown> breakdown
    ) {
        public static Summary empty() {
            return new Summary(TenantCreditAccount.TIER_FREE, TenantCreditAccount.FREE_MONTHLY_QUOTA,
                    0, 0, 0, TenantCreditAccount.FREE_MONTHLY_QUOTA,
                    null, null, java.util.List.of());
        }
    }

    public record Breakdown(String actionType, int credits) {
    }

    // ----- Provisioning -----------------------------------------------------

    /** Called once at tenant create — issues the account with a Free-tier
     *  allocation and charges the one-time AI provisioning fee (10 credits)
     *  the classifier already consumed during the onboarding wizard. */
    @Transactional
    public TenantCreditAccount provisionAccount(UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        TenantCreditAccount account = accounts.save(
                new TenantCreditAccount(tenantId, now, now.plus(CYCLE_LENGTH)));

        // Book-keep the AI provisioning run against the fresh account. Direct
        // atomic decrement rather than going through consume() because we own
        // the transaction + we know the account was just created with the
        // right headroom (defensive: consume() would fail-loud if not).
        int cost = CreditActions.costOf(CreditActions.AI_PROVISIONING);
        int affected = accounts.consumeIfAvailable(tenantId, cost, now);
        if (affected == 1) {
            transactions.save(CreditTransaction.consumed(
                    tenantId, CreditActions.AI_PROVISIONING, cost, null, "signup", now));
        } else {
            log.warn("Could not charge AI_PROVISIONING to fresh account for tenant {}", tenantId);
        }
        return account;
    }

    // ----- internals --------------------------------------------------------

    /** Lazy cycle rollover — checked on every consume/reserve. The
     *  underlying UPDATE only fires when the row's end date is in the past,
     *  so this is a no-op the other 99% of the time and idempotent if two
     *  threads race the rollover. */
    private void rollCycleIfExpired(UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        accounts.rollCycle(tenantId, now, now.plus(CYCLE_LENGTH));
    }
}
