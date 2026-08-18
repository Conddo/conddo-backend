package io.conddo.core.integrations;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Adapter for the OPay merchant Checkout API (v3). Each tenant supplies
 * their own credential triple — {@code merchantId} (15-digit numeric),
 * {@code OPAYPRV…} private key (signing), and {@code OPAYPUB…} public
 * key (authorization header) — so every call takes them as parameters.
 *
 * <p>{@link #verify} validates each field's format separately (so the
 * UI can say exactly which field is wrong) and then hits OPay's
 * merchant-verify endpoint for a live check. Inbound money is
 * webhook-primary for OPay — {@link #fetchTransactions} is intentionally
 * a no-op until OPay documents a list-collections endpoint; the poller
 * skips OPay accounts.
 */
public interface OpayGateway {

    /** One credential field failed format validation or the live check. */
    class VerificationException extends RuntimeException {
        public VerificationException(String msg) {
            super(msg);
        }
    }

    /** The provider call itself failed (timeout, 5xx, bad JSON). */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * Format-check + live-verify the credential triple. Returns the
     * merchant snapshot OPay reports on success.
     */
    MerchantInfo verify(String merchantId, String privateKey, String publicKey);

    /** Best-effort polling feed — currently a no-op (webhook-primary). */
    List<TransferRecord> fetchTransactions(String merchantId, String publicKey, OffsetDateTime since);

    /** HMAC-SHA512 over the raw body with the configured webhook secret. */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /** True when a base URL is configured and calls are attempted. */
    boolean isConfigured();

    /** Provider-returned merchant snapshot for the connected row. */
    record MerchantInfo(String businessName, String accountName, String accountNumber,
                        String bankName, String terminalSerial) {
    }

    /** One inbound collection transaction, normalised to kobo. */
    record TransferRecord(String providerReference, String senderName,
                          String senderAccountNumber, String senderBank,
                          long amountKobo, String currency, OffsetDateTime receivedAt,
                          String rawJson) {
    }
}
