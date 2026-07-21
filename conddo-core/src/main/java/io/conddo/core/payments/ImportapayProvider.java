package io.conddo.core.payments;

import io.conddo.core.domain.PaymentIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Importapay integration — customer &rarr; tenant online collections.
 * Phase 0 skeleton: signature verification is real (HMAC-SHA512 same as
 * Paystack); charge initiation, verification, and refunds throw until
 * Phase 2 wires the real Importapay SDK / REST calls.
 */
@Component
public class ImportapayProvider implements PaymentProvider {

    private final String webhookSecret;

    public ImportapayProvider(
            @Value("${conddo.payments.importapay.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override public String providerName() { return PaymentIntent.PROVIDER_IMPORTAPAY; }

    @Override
    public PaymentIntent initiateCharge(PaymentIntent intent) {
        throw new UnsupportedOperationException("Importapay charge initiation not yet wired");
    }

    @Override
    public PaymentIntent verifyCharge(PaymentIntent intent) {
        throw new UnsupportedOperationException("Importapay verify not yet wired");
    }

    @Override
    public void refund(PaymentIntent intent, long amountKobo, String reason) {
        throw new UnsupportedOperationException("Importapay refund not yet wired");
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return WebhookSignature.verifyHmacSha512(rawBody, signature, webhookSecret);
    }
}
