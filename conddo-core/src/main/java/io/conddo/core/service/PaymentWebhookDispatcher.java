package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.PaymentEvent;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.payments.PaymentProviders;
import io.conddo.core.repository.PaymentIntentRepository;
import io.conddo.core.tenant.TenantScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Second-stage handler for payment webhooks. {@link PaymentEventIngestService}
 * persists the raw event; this class picks it up, resolves the intent it
 * refers to, asks the provider for authoritative status, updates the
 * intent, and fans out to origin.
 *
 * <p>Origin fan-out (mark orders paid, mark invoices settled, extend
 * subscriptions, fire receipt emails) is intentionally sketched here —
 * each origin's mark-paid hook lands as its feature wires up. Until then
 * the intent status is authoritative and origins can poll off it.
 */
@Service
public class PaymentWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookDispatcher.class);

    private final PaymentIntentRepository intents;
    private final PaymentProviders providers;
    private final ObjectMapper objectMapper;

    public PaymentWebhookDispatcher(PaymentIntentRepository intents,
                                    PaymentProviders providers,
                                    ObjectMapper objectMapper) {
        this.intents = intents;
        this.providers = providers;
        this.objectMapper = objectMapper;
    }

    /**
     * Process an ingested event. Returns quickly (200 to the provider)
     * even on unknown intents — an event for a reference we don't own
     * usually means a legacy Paystack subscription webhook landing on
     * the new endpoint, or a test event from the provider console.
     */
    @Transactional
    @TenantScoped(crossTenant = true)
    public void dispatch(PaymentEvent event) {
        JsonNode body;
        try {
            body = objectMapper.readTree(event.getRawBody());
        } catch (IOException ex) {
            log.warn("payment webhook {}: raw body not parseable, giving up", event.getProvider());
            return;
        }

        UUID intentId = resolveIntentId(body);
        Optional<PaymentIntent> found = intentId != null
                ? intents.findById(intentId)
                : findByProviderReference(body);
        if (found.isEmpty()) {
            log.info("payment webhook {}: no matching intent (event {})",
                    event.getProvider(), event.getEventType());
            return;
        }

        PaymentIntent intent = found.get();
        event.setPaymentIntentId(intent.getId());

        String type = event.getEventType() == null ? "" : event.getEventType().toLowerCase();

        // Success events → re-verify authoritatively via the provider,
        // never trust the webhook body's status field alone. This is the
        // standard safeguard against a forged webhook slipping past
        // signature verification.
        if (type.contains("success") || type.contains("charge.completed")) {
            PaymentIntent verified = providers.require(intent.getProvider()).verifyCharge(intent);
            intents.save(verified);
            fanOutSuccess(verified);
            return;
        }

        // Explicit failures we can trust the type on — no verify round-trip.
        if (type.contains("failed") || type.contains("charge.failed")) {
            String reason = nullIfBlank(body.path("data").path("gateway_response").asText(null));
            intent.markFailed(reason);
            intents.save(intent);
            return;
        }

        // Refund events → mark refunded (full vs partial derived from
        // whatever the provider reported).
        if (type.contains("refund")) {
            long refunded = body.path("data").path("amount").asLong(0);
            boolean partial = refunded > 0 && refunded < intent.getAmountKobo();
            intent.markRefunded(partial);
            intents.save(intent);
            return;
        }

        log.info("payment webhook {}: no handler for {}", event.getProvider(), type);
    }

    /**
     * Fan-out on success. Each origin's mark-paid gets wired here as its
     * feature ships — order flip in Phase 2b, invoice flip already lives
     * on {@link InvoiceService#markPaidByGateway}, subscription renewal
     * in Phase 2c. For now this is a switch on origin with logging.
     */
    private void fanOutSuccess(PaymentIntent intent) {
        switch (intent.getOrigin()) {
            case PaymentIntent.ORIGIN_ORDER -> log.info(
                    "TODO: mark order {} paid via intent {}", intent.getOriginOrderId(), intent.getId());
            case PaymentIntent.ORIGIN_INVOICE -> log.info(
                    "TODO: mark invoice {} paid via intent {}", intent.getOriginInvoiceId(), intent.getId());
            case PaymentIntent.ORIGIN_BOOKING -> log.info(
                    "TODO: mark booking {} paid via intent {}", intent.getOriginBookingId(), intent.getId());
            case PaymentIntent.ORIGIN_SUBSCRIPTION -> log.info(
                    "TODO: extend subscription for tenant {} via intent {}", intent.getTenantId(), intent.getId());
            case PaymentIntent.ORIGIN_LINK -> log.info(
                    "TODO: bump link {} totals via intent {}", intent.getOriginLinkId(), intent.getId());
            default -> log.info("payment intent {} succeeded (origin={})", intent.getId(), intent.getOrigin());
        }
    }

    private UUID resolveIntentId(JsonNode body) {
        // We stamp our own intent_id into metadata at init time — see
        // ImportapayProvider.initiateCharge. Path may be `metadata.intent_id`
        // or `data.metadata.intent_id` depending on how the provider
        // wraps the payload; check both.
        String id = firstNonBlank(
                body.path("metadata").path("intent_id").asText(null),
                body.path("data").path("metadata").path("intent_id").asText(null));
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Optional<PaymentIntent> findByProviderReference(JsonNode body) {
        String ref = firstNonBlank(
                body.path("data").path("reference").asText(null),
                body.path("reference").asText(null),
                body.path("data").path("transaction_reference").asText(null));
        if (ref == null) return Optional.empty();
        return intents.findByProviderReference(ref);
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
