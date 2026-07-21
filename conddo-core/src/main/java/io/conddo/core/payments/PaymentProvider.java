package io.conddo.core.payments;

import io.conddo.core.domain.PaymentIntent;

/**
 * Provider-agnostic payment abstraction. Feature code (order checkout,
 * invoice pay-now, POS collection, subscription billing) never touches a
 * provider SDK directly — it creates a {@link PaymentIntent} and hands
 * it to the appropriate {@code PaymentProvider} implementation for
 * checkout initiation, verification, refunds, and webhook signature
 * checks.
 *
 * <p>Three implementations exist:
 * <ul>
 *   <li>{@code PaystackProvider}   — tenant &rarr; Conddo subscriptions</li>
 *   <li>{@code ImportapayProvider} — customer &rarr; tenant online</li>
 *   <li>{@code RoutepayProvider}   — customer &rarr; tenant offline / POS</li>
 * </ul>
 *
 * <p>Implementations MUST be idempotent — the same intent handed to
 * {@link #initiateCharge} twice should return the same checkout URL,
 * not spawn a duplicate provider transaction.
 */
public interface PaymentProvider {

    /** The {@link PaymentIntent#getProvider()} value this implementation handles. */
    String providerName();

    /**
     * Kick off a charge with the provider. Populates
     * {@code providerReference} and {@code checkoutUrl} on the intent
     * and returns it. Persistence is the caller's responsibility.
     *
     * @param intent a freshly-created intent with tenant, amount, customer, origin filled
     * @return the same intent with provider handles attached
     */
    PaymentIntent initiateCharge(PaymentIntent intent);

    /**
     * Ask the provider whether a charge actually succeeded. Used for:
     * (a) FE polling on the customer-facing return page,
     * (b) reconciliation cron for intents stuck in {@code pending},
     * (c) manual admin re-verification.
     *
     * @return an updated intent with status, fee, netKobo, timestamps
     */
    PaymentIntent verifyCharge(PaymentIntent intent);

    /**
     * Refund a successful charge, in full or partial. The intent's
     * status will flip to {@code refunded} or {@code partially_refunded}
     * once the provider confirms.
     *
     * @param intent    the succeeded intent to refund
     * @param amountKobo full amount to refund; must be &le; the intent's amountKobo
     * @param reason    audit trail
     */
    void refund(PaymentIntent intent, long amountKobo, String reason);

    /**
     * Verify a webhook payload came from the provider. Returns false
     * silently on mismatch; controllers return 401 without a body so
     * probing attackers learn nothing.
     */
    boolean verifyWebhookSignature(String rawBody, String signature);
}
