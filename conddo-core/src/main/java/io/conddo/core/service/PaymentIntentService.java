package io.conddo.core.service;

import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.payments.PaymentProvider;
import io.conddo.core.payments.PaymentProviders;
import io.conddo.core.repository.PaymentIntentRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final InvoiceService invoiceService;

    @PersistenceContext
    private EntityManager em;

    public PaymentIntentService(PaymentIntentRepository intents,
                                PaymentProviders providers,
                                InvoiceService invoiceService) {
        this.intents = intents;
        this.providers = providers;
        this.invoiceService = invoiceService;
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

    // ------------- public / customer-facing flows -------------------------
    //
    // These run without a JWT — the caller is an end customer clicking
    // "Pay online" on an invoice, order, or booking. Every method sets
    // the {@code app.public_resolver} GUC so RLS lets the intent row
    // through despite no tenant being bound.

    /**
     * Kick off a new payment intent from an invoice's public share
     * token. Called when the customer clicks "Pay online" on
     * {@code /i/{token}}. Idempotent per invoice — returns the existing
     * pending intent if one already exists so a double-click doesn't
     * spawn two receiving accounts.
     *
     * @return the intent ready for the FE to redirect to {@code /pay/{intentId}}
     */
    @Transactional
    public PaymentIntent startForInvoice(String invoiceToken) {
        InvoiceService.PublicView view = invoiceService.resolveByPublicToken(invoiceToken)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        Invoice invoice = view.invoice();
        if (Invoice.STATUS_PAID.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("This invoice is already paid");
        }
        if (Invoice.STATUS_VOID.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("This invoice has been voided");
        }

        // Rebind public resolver — resolveByPublicToken set it, but any
        // additional queries in this method need it live.
        em.createNativeQuery("SET LOCAL app.public_resolver = 'true'").executeUpdate();

        // Re-use existing pending intent if one exists for this invoice.
        List<PaymentIntent> existing = intents.findByOriginInvoiceId(invoice.getId());
        for (PaymentIntent i : existing) {
            if (PaymentIntent.STATUS_PENDING.equals(i.getStatus())) return i;
        }

        PaymentIntent intent = new PaymentIntent();
        intent.setTenantId(invoice.getTenantId());
        intent.setProvider(PaymentIntent.PROVIDER_IMPORTAPAY);
        intent.setOrigin(PaymentIntent.ORIGIN_INVOICE);
        intent.setOriginInvoiceId(invoice.getId());
        intent.setOriginReference("Invoice " + invoice.getInvoiceNumber());
        intent.setAmountKobo(invoice.getTotalKobo() - amountAlreadyPaid(existing));
        intent.setCurrency(invoice.getCurrency());
        intent.setCustomerId(invoice.getCustomerId());
        intent.setCustomerName(invoice.getCustomerName());
        intent.setCustomerEmail(invoice.getCustomerEmail());
        intent.setCustomerPhone(invoice.getCustomerPhone());
        intent.setIdempotencyKey("invoice:" + invoice.getId());
        intent = intents.save(intent);

        PaymentIntent primed = providers.require(intent.getProvider()).initiateCharge(intent);
        return intents.save(primed);
    }

    private long amountAlreadyPaid(List<PaymentIntent> siblings) {
        return siblings.stream()
                .filter(i -> PaymentIntent.STATUS_SUCCEEDED.equals(i.getStatus())
                        || PaymentIntent.STATUS_PARTIALLY_REFUNDED.equals(i.getStatus()))
                .mapToLong(PaymentIntent::getAmountKobo)
                .sum();
    }

    /** Public read of a single intent by ID. Used to render {@code /pay/{id}}. */
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> resolvePublic(UUID intentId) {
        em.createNativeQuery("SET LOCAL app.public_resolver = 'true'").executeUpdate();
        return intents.findById(intentId);
    }

    /**
     * Customer clicked "I have paid" — POST to the provider with the
     * sender bank + sender account so they can match against inbound
     * credits. Returns the intent with an updated status.
     */
    @Transactional
    public PaymentIntent confirmPublic(UUID intentId, String senderBank, String senderAccountNumber) {
        em.createNativeQuery("SET LOCAL app.public_resolver = 'true'").executeUpdate();
        PaymentIntent intent = intents.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        PaymentIntent updated = providers.require(intent.getProvider())
                .confirmPayment(intent, senderBank, senderAccountNumber);
        return intents.save(updated);
    }

    /** Customer-side polling for status when confirmPayment returned
     *  {@code awaiting_confirmation}. */
    @Transactional
    public PaymentIntent verifyPublic(UUID intentId) {
        em.createNativeQuery("SET LOCAL app.public_resolver = 'true'").executeUpdate();
        PaymentIntent intent = intents.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        if (PaymentIntent.STATUS_SUCCEEDED.equals(intent.getStatus())
                || PaymentIntent.STATUS_FAILED.equals(intent.getStatus())
                || PaymentIntent.STATUS_REFUNDED.equals(intent.getStatus())) {
            // Terminal — no need to burn a provider round-trip.
            return intent;
        }
        PaymentIntent verified = providers.require(intent.getProvider()).verifyCharge(intent);
        return intents.save(verified);
    }

    /** Bank pick-list for the sender-bank dropdown. Provider-agnostic —
     *  same shape regardless of which PSP powers the intent. */
    @Transactional(readOnly = true)
    public List<PaymentProvider.BankOption> supportedBanks(String providerName) {
        return providers.require(providerName).supportedBanks();
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
