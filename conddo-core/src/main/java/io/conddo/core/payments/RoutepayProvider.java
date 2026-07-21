package io.conddo.core.payments;

import io.conddo.core.domain.PaymentIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Routepay integration — customer &rarr; tenant offline / POS collections.
 * Phase 0 skeleton. Same shape as {@code ImportapayProvider}.
 */
@Component
public class RoutepayProvider implements PaymentProvider {

    private final String webhookSecret;

    public RoutepayProvider(
            @Value("${conddo.payments.routepay.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override public String providerName() { return PaymentIntent.PROVIDER_ROUTEPAY; }

    @Override
    public PaymentIntent initiateCharge(PaymentIntent intent) {
        throw new UnsupportedOperationException("Routepay charge initiation not yet wired");
    }

    @Override
    public PaymentIntent verifyCharge(PaymentIntent intent) {
        throw new UnsupportedOperationException("Routepay verify not yet wired");
    }

    @Override
    public void refund(PaymentIntent intent, long amountKobo, String reason) {
        throw new UnsupportedOperationException("Routepay refund not yet wired");
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return WebhookSignature.verifyHmacSha512(rawBody, signature, webhookSecret);
    }
}
