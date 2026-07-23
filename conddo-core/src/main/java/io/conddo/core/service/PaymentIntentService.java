package io.conddo.core.service;

import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.payments.PaymentProviders;
import io.conddo.core.repository.PaymentIntentRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The single entry point every payment-collecting surface uses. Order
 * checkout, invoice pay-now, booking deposit, POS collection, payment
 * link click, subscription renewal — they all call {@link #createAndInitiate}
 * with an origin and get back a persisted intent whose {@code checkoutUrl}
 * they hand to the customer (or, for recurring auth, a pre-succeeded intent
 * they can just record).
 *
 * <p>Idempotency: callers pass an {@code idempotencyKey} scoped to the
 * business action ({@code "order:" + orderId + ":attempt:1"}). Re-submits
 * with the same key return the existing intent instead of spawning a
 * duplicate charge. This is the single defence against a double-click
 * on Pay Now creating two provider transactions.
 */
@Service
public class PaymentIntentService {

    private final PaymentIntentRepository intents;
    private final PaymentProviders providers;

    public PaymentIntentService(PaymentIntentRepository intents, PaymentProviders providers) {
        this.intents = intents;
        this.providers = providers;
    }

    /**
     * Create an intent + kick off the provider charge in one step. Returns
     * the persisted intent with {@code checkoutUrl} + {@code providerReference}
     * populated (or, for recurring-auth charges, with a final status set).
     */
    @Transactional
    @TenantScoped
    public PaymentIntent createAndInitiate(NewIntent input) {
        UUID tenantId = TenantContext.require();

        // Idempotency short-circuit. If a caller has already asked for
        // this exact action, hand back the existing intent — never spawn
        // a second charge.
        if (input.idempotencyKey() != null) {
            Optional<PaymentIntent> existing =
                    intents.findByTenantIdAndIdempotencyKey(tenantId, input.idempotencyKey());
            if (existing.isPresent()) return existing.get();
        }

        PaymentIntent intent = new PaymentIntent();
        intent.setTenantId(tenantId);
        intent.setProvider(input.provider());
        intent.setOrigin(input.origin());
        intent.setOriginOrderId(input.originOrderId());
        intent.setOriginBookingId(input.originBookingId());
        intent.setOriginInvoiceId(input.originInvoiceId());
        intent.setOriginLinkId(input.originLinkId());
        intent.setOriginReference(input.originReference());
        intent.setAmountKobo(input.amountKobo());
        intent.setCurrency(input.currency() != null ? input.currency() : "NGN");
        intent.setCustomerId(input.customerId());
        intent.setCustomerName(input.customerName());
        intent.setCustomerEmail(input.customerEmail());
        intent.setCustomerPhone(input.customerPhone());
        intent.setIdempotencyKey(input.idempotencyKey());
        intent.setAuthorizationCode(input.authorizationCode());
        intent = intents.save(intent);

        // Hand off to the provider. The adapter mutates the intent with
        // checkoutUrl + providerReference (or final status for recurring
        // auth); we persist those in the same transaction.
        PaymentIntent primed = providers.require(input.provider()).initiateCharge(intent);
        return intents.save(primed);
    }

    /** Re-verify a stuck intent against the provider. Used by the recon
     *  cron and the FE polling on the customer return page. */
    @Transactional
    @TenantScoped
    public PaymentIntent verify(UUID intentId) {
        PaymentIntent intent = intents.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("No payment intent " + intentId));
        PaymentIntent verified = providers.require(intent.getProvider()).verifyCharge(intent);
        return intents.save(verified);
    }

    /**
     * Inputs for {@link #createAndInitiate}. Exactly one of the origin_*
     * fields should be non-null; the rest optional. Amount is required
     * and must be positive.
     */
    public record NewIntent(
            String provider,
            String origin,
            long amountKobo,
            String currency,
            UUID customerId,
            String customerName,
            String customerEmail,
            String customerPhone,
            UUID originOrderId,
            UUID originBookingId,
            UUID originInvoiceId,
            UUID originLinkId,
            String originReference,
            String idempotencyKey,
            /** Pre-existing auth token — pass to charge a saved card silently
             *  (subscription renewal). Null for a normal customer checkout. */
            String authorizationCode
    ) {}
}
