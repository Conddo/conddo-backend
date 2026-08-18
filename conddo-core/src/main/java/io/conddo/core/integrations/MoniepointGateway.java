package io.conddo.core.integrations;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Adapter for the Moniepoint merchant API (Monnify rails). Each tenant
 * supplies their own merchant api key ({@code mp_live_…}) — there is no
 * platform-level key, so every call takes the tenant's key as a
 * parameter.
 *
 * <p>Two real call shapes are used:
 * <ol>
 *   <li>{@link #verify} — GET /api/v1/transactions/search with a page
 *       size of 1. A non-2xx or {@code requestSuccessful=false} means
 *       the key is invalid / revoked; success returns the merchant
 *       name for the connected-account row.</li>
 *   <li>{@link #fetchTransactions} — the same search endpoint paged,
 *       filtered to {@code PAID}, used by the poller to feed
 *       {@code /transfers/incoming}.</li>
 * </ol>
 *
 * <p>Webhook verification is HMAC-SHA512 over the raw body with the
 * platform-configured {@code conddo.integrations.moniepoint.webhook-secret}
 * (Moniepoint stamps it in the {@code monnify-signature} header). When
 * the secret is blank, {@link #verifyWebhookSignature} returns false so
 * nothing unsigned is ever processed.
 */
public interface MoniepointGateway {

    /** Thrown when Moniepoint rejects the key (401/403) or reports failure. */
    class VerificationException extends RuntimeException {
        public VerificationException(String msg) {
            super(msg);
        }
    }

    /** Thrown when the provider call itself fails (timeout, 5xx, bad JSON). */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * Verify a merchant api key. Returns the merchant snapshot Moniepoint
     * reports (business name + any terminal/account identifiers the
     * search payload carries).
     */
    MerchantInfo verify(String apiKey);

    /**
     * Pull paid transactions since {@code since} (polling feed).
     * Returns an empty list when the account has no matching rows.
     */
    List<TransferRecord> fetchTransactions(String apiKey, OffsetDateTime since);

    /** HMAC-SHA512 over the raw body with the configured webhook secret. */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /** True when a base URL is configured and calls are attempted. */
    boolean isConfigured();

    /** Provider-returned merchant/terminal snapshot for the connected row. */
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
