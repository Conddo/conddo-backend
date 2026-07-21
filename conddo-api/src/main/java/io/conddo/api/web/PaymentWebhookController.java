package io.conddo.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.PaymentEvent;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.payments.PaymentProvider;
import io.conddo.core.payments.PaymentProviders;
import io.conddo.core.service.PaymentEventIngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Universal payment webhook receiver. One endpoint per provider; each
 * verifies its own HMAC signature, ingests the raw event verbatim,
 * and hands off to the dispatcher for business-side reactions.
 *
 * <p>Phase 0 skeleton: signature verification + persistence + dedupe
 * are wired end-to-end. Business dispatch (marking orders paid,
 * invoices settled, sending receipts, etc.) lands in Phase 2 when the
 * first live provider integration ships.
 *
 * <p>Endpoint shape: {@code POST /api/v1/webhooks/payments/{provider}}
 * where {@code provider} ∈ {@code paystack | importapay | routepay}.
 * Existing {@link PaystackWebhookController} continues to serve the
 * legacy subscription flow at {@code /api/v1/billing/webhooks/paystack}
 * until Phase 5 migrates it to this rail.
 */
@RestController
@RequestMapping("/api/v1/webhooks/payments")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentProviders providers;
    private final PaymentEventIngestService ingest;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(PaymentProviders providers,
                                    PaymentEventIngestService ingest,
                                    ObjectMapper objectMapper) {
        this.providers = providers;
        this.ingest = ingest;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestBody String rawBody,
                                        HttpServletRequest request) {

        PaymentProvider prov;
        try {
            prov = providers.require(providerKey(provider));
        } catch (IllegalArgumentException ex) {
            log.warn("payment webhook: unknown provider {}", provider);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String signature = signatureHeader(prov.providerName(), request);
        if (!prov.verifyWebhookSignature(rawBody, signature)) {
            log.warn("payment webhook {}: signature mismatch", prov.providerName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String headersJson = capturedHeadersAsJson(request);
        PaymentEventIngestService.Result result = ingest.ingest(prov.providerName(), rawBody, headersJson);

        if (!result.newlyStored()) {
            // Retry of an already-processed event; return 200 so the
            // provider stops retrying.
            return ResponseEntity.ok().build();
        }

        try {
            dispatch(prov, result.event());
            ingest.markProcessed(result.event());
        } catch (RuntimeException ex) {
            log.error("payment webhook {}: dispatch failed: {}", prov.providerName(), ex.getMessage());
            ingest.markFailed(result.event(), ex.getMessage());
            // Return 200 anyway — the raw event is on disk, the
            // reprocessor cron will retry. Returning 5xx just invites
            // provider retry storms that we can't respond to any
            // better than what's already on disk.
        }
        return ResponseEntity.ok().build();
    }

    /** Phase 0 stub — real dispatch (mark order paid, send receipt,
     *  fan-out to origin feature) lands with the Importapay integration
     *  in Phase 2. For now we just log the event type so integrations
     *  under test can see their hooks land. */
    private void dispatch(PaymentProvider prov, PaymentEvent event) {
        log.info("payment webhook {}: received {} (event {})",
                prov.providerName(), event.getEventType(), event.getProviderEventId());
    }

    /** Header where each provider stamps their signature. */
    private static String signatureHeader(String provider, HttpServletRequest req) {
        return switch (provider) {
            case PaymentIntent.PROVIDER_PAYSTACK -> req.getHeader("x-paystack-signature");
            case PaymentIntent.PROVIDER_IMPORTAPAY -> req.getHeader("x-importapay-signature");
            case PaymentIntent.PROVIDER_ROUTEPAY -> req.getHeader("x-routepay-signature");
            default -> null;
        };
    }

    /** Normalise path aliases so /webhooks/payments/Importapay etc still work. */
    private static String providerKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private String capturedHeadersAsJson(HttpServletRequest req) {
        Map<String, String> h = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            // Skip cookies + auth just in case a provider ever forwards
            // something we don't want persisted verbatim.
            if (name.equalsIgnoreCase("cookie") || name.equalsIgnoreCase("authorization")) continue;
            h.put(name, req.getHeader(name));
        }
        try {
            return objectMapper.writeValueAsString(h);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
