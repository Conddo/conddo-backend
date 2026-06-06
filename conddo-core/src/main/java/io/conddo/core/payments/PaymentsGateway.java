package io.conddo.core.payments;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for talking to <b>Conddo Payments</b> — the standalone payments service
 * that wraps RoutePay (ACTION_LIST §7a). The platform (Control plane) tells
 * payments who a tenant is; payments owns the money flow.
 *
 * <p>Same shape as {@link io.conddo.core.studio.StudioJobGateway}: HTTP call
 * implemented in {@code conddo-api}, fail-safe (returns
 * {@link Optional#empty()} on transport error so signup never depends on the
 * payments service being up).
 */
public interface PaymentsGateway {

    /**
     * Provision a tenant's RoutePay sub-account. Idempotent — re-calling for the
     * same {@code tenantId} returns the existing record. The handle's
     * {@code subaccountId} is null while the sub-account is
     * {@code PROVISIONING_FAILED}; in that case a manual retry from Studio Admin
     * or a periodic reconciliation job is expected to flip it.
     */
    Optional<TenantPaymentsAccount> provisionTenantAccount(UUID tenantId, String tenantSlug,
                                                           String businessName, String contactEmail);

    /**
     * Initialise a payment intent against a tenant's sub-account, attached to a
     * specific booking (MS-2 deposit-at-booking). Returns the checkout URL the
     * FE redirects the customer to. Empty when the payments service is
     * unreachable — the booking stays {@code PENDING_DEPOSIT} so a retry can
     * generate a fresh URL.
     */
    Optional<PaymentInitResult> initBookingDeposit(UUID tenantId, String tenantSlug,
                                                   UUID bookingId, UUID customerId,
                                                   String customerEmail, String customerName,
                                                   long amountKobo, String description, String returnUrl);

    /**
     * Initialise a creative-service charge (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5).
     * Same shape as the booking deposit but charged directly to the merchant
     * (not their customer) — payment lands in our master account, not the
     * tenant's RoutePay sub-account. Empty result keeps the request in
     * {@code pending_payment} so the FE can re-fetch a fresh URL.
     */
    Optional<PaymentInitResult> initCreativeServiceCharge(UUID tenantId, String tenantSlug,
                                                          UUID requestId, UUID userId,
                                                          String userEmail, String userName,
                                                          long amountKobo, String description, String returnUrl);

    /**
     * Initialise a brand-package charge (SOCIAL_AND_CREATIVE_SERVICES_SPEC §6).
     * Routes to the platform account (kind=BRAND_PACKAGE in conddo-payments V2).
     * Used both for the initial subscription charge and recurring renewals.
     */
    Optional<PaymentInitResult> initBrandPackageCharge(UUID tenantId, String tenantSlug,
                                                       UUID subscriptionId, UUID userId,
                                                       String userEmail, String userName,
                                                       long amountKobo, String description, String returnUrl);

    /** Minimal handle to a provisioned tenant account, returned across the seam. */
    record TenantPaymentsAccount(UUID tenantId, String subaccountId, String status) {
    }

    /** The checkout URL + the reference the FE polls to verify the payment after the customer returns. */
    record PaymentInitResult(String reference, String paymentUrl, String status) {
    }
}
