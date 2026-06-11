package io.conddo.core.paystack;

import java.math.BigDecimal;

/**
 * Adapter for Paystack used by the Conddo plan billing flow
 * (HANDOFF_2026-06-11 §8). Hosted-checkout init + transaction verify
 * are the two real call shapes; webhook signature verification lives
 * here too since it needs the secret key.
 */
public interface PaystackGateway {

    /**
     * Initialise a hosted checkout. {@code amountKobo} is the total
     * in kobo (₦1 = 100 kobo). Returns the hosted URL the customer
     * is redirected to, the {@code reference} we persist, and an
     * optional {@code accessCode} the FE can use for Paystack's
     * inline JS if it ever swaps from the redirect flow.
     */
    InitResult initialize(String email, long amountKobo, String reference,
                          String callbackUrl, java.util.Map<String, Object> metadata);

    /**
     * Verify a transaction by reference. Source of truth for
     * {@code /api/v1/billing/verify} when the webhook hasn't landed
     * yet.
     */
    VerifyResult verify(String reference);

    /**
     * Disable a Paystack subscription so renewals stop. Current row
     * stays active until {@code expires_at} per the spec — the
     * disable just halts billing.
     */
    void disableSubscription(String subscriptionCode, String emailToken);

    /** True if the signature on the inbound webhook matches the secret. */
    boolean verifyWebhookSignature(String body, String signature);

    /** Thrown when Paystack returns an error or the call times out. */
    class PaystackUnavailableException extends RuntimeException {
        public PaystackUnavailableException(String msg) {
            super(msg);
        }

        public PaystackUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** Thrown when the gateway is not configured on this deployment. */
    class PaystackNotConfiguredException extends RuntimeException {
        public PaystackNotConfiguredException() {
            super("Paystack is not configured on this deployment.");
        }
    }

    record InitResult(String authorizationUrl, String reference, String accessCode) {
    }

    record VerifyResult(String reference, Status status, BigDecimal amountNgn,
                        java.time.OffsetDateTime paidAt, String failureReason,
                        String subscriptionCode, String customerCode) {

        public enum Status {
            SUCCESS, PENDING, FAILED, ABANDONED
        }
    }
}
