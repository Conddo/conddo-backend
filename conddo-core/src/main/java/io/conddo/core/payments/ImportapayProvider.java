package io.conddo.core.payments;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.core.domain.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Importapay adapter.
 *
 * <p>Importapay is a Nigerian bank-transfer PSP — not a hosted-checkout /
 * card-tokenising PSP. Flow:
 *
 * <ol>
 *   <li>{@link #initiateCharge} → POST {@code /payment-intents} → the API
 *       returns a static <b>receiving account</b> (bank + account number
 *       + name). We store those on the intent; the FE renders them for
 *       the customer to transfer to.</li>
 *   <li>Customer transfers via their own bank / USSD.</li>
 *   <li>{@link #confirmPayment} → POST {@code /:id/confirm-payment} with
 *       sender bank + sender account. Importapay matches inbound credits
 *       against the receiving account and returns a resolved status.</li>
 *   <li>{@link #verifyCharge} → POST {@code /:id/verify} for polling when
 *       {@code confirm-payment} returned {@code awaiting_confirmation}.</li>
 * </ol>
 *
 * <p><b>Recurring authorization is not supported.</b> Importapay has no
 * card tokenization, so subscription auto-renew via saved credentials
 * is impossible. Renewals must go through a fresh intent + fresh
 * customer transfer each cycle.
 *
 * <p><b>Refunds not documented</b> in the current integration guide.
 * The refund method throws until either Importapay ships a refund
 * endpoint or we settle on an out-of-band process.
 *
 * <p><b>Webhooks are optional</b> per the docs. This adapter treats them
 * as best-effort and relies primarily on {@code verifyCharge} polling
 * as the source of truth.
 */
@Component
public class ImportapayProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(ImportapayProvider.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final String apiKey;
    private final String webhookSecret;
    private final boolean enabled;

    public ImportapayProvider(
            @Value("${conddo.payments.importapay.base-url:https://importa-pay-payments-x72y4.ondigitalocean.app/api}") String baseUrl,
            @Value("${conddo.payments.importapay.api-key:}") String apiKey,
            @Value("${conddo.payments.importapay.webhook-secret:}") String webhookSecret,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret;
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (enabled) {
            log.info("ImportapayProvider active (base={})", baseUrl);
        } else {
            log.info("ImportapayProvider is dormant — set CONDDO_PAYMENTS_IMPORTAPAY_API_KEY to enable");
        }
    }

    @Override public String providerName() { return PaymentIntent.PROVIDER_IMPORTAPAY; }

    @Override
    public PaymentIntent initiateCharge(PaymentIntent intent) {
        requireEnabled();
        if (intent.getAuthorizationCode() != null) {
            // Recurring auth path — Importapay doesn't support it. The
            // subscription renewal service should never route here; if
            // it does, refuse loudly rather than silently spawn a fresh
            // manual-transfer intent the customer will never see.
            throw new UnsupportedOperationException(
                    "Importapay does not support recurring authorization — subscription renewals "
                            + "must spawn a new customer-facing intent with a fresh transfer.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantReference", intent.getId().toString());
        body.put("amount", intent.getAmountKobo());
        if (intent.getCustomerName() != null || intent.getOriginReference() != null) {
            String description = intent.getOriginReference() != null
                    ? intent.getOriginReference()
                    : ("Payment for " + intent.getCustomerName());
            body.put("description", description);
        }

        JsonNode data = post("/merchant/payment-intents", body).path("response");
        intent.setProviderReference(nullIfBlank(data.path("paymentIntentId").asText(null)));
        intent.setReceivingBankName(nullIfBlank(data.path("bankName").asText(null)));
        intent.setReceivingAccountNumber(nullIfBlank(data.path("accountNumber").asText(null)));
        intent.setReceivingAccountName(nullIfBlank(data.path("accountName").asText(null)));
        // No checkout URL — the FE renders the receiving account inline.
        intent.setCheckoutUrl(null);
        return intent;
    }

    @Override
    public PaymentIntent confirmPayment(PaymentIntent intent, String senderBank, String senderAccountNumber) {
        requireEnabled();
        if (intent.getProviderReference() == null) {
            throw new IllegalStateException("Cannot confirm — intent has no Importapay reference");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderBank", senderBank);
        body.put("senderAccountNumber", senderAccountNumber);

        JsonNode response = post("/merchant/payment-intents/" + intent.getProviderReference() + "/confirm-payment", body)
                .path("response");
        intent.setSenderBankName(senderBank);
        intent.setSenderAccountNumber(senderAccountNumber);
        applyStatus(intent, response.path("status").asText(""), response);
        return intent;
    }

    @Override
    public PaymentIntent verifyCharge(PaymentIntent intent) {
        requireEnabled();
        if (intent.getProviderReference() == null) {
            throw new IllegalStateException("Cannot verify — intent has no Importapay reference");
        }
        JsonNode response = post("/merchant/payment-intents/" + intent.getProviderReference() + "/verify",
                new LinkedHashMap<>()).path("response");
        applyStatus(intent, response.path("status").asText(""), response);
        return intent;
    }

    @Override
    public void refund(PaymentIntent intent, long amountKobo, String reason) {
        // The current Importapay merchant-API docs do not expose a refund
        // endpoint. Overpayment refunds happen automatically (returned in
        // {@code confirmPayment}'s response as {@code refundAmount}) but
        // merchant-initiated refunds are out of band today.
        throw new UnsupportedOperationException(
                "Importapay does not currently expose a refund endpoint — handle refunds out of band");
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        // Webhooks are optional in the current Importapay docs and the
        // signature scheme isn't documented. Falls back to constant-false
        // when no webhook secret is configured — controllers will reject
        // 401 and we lean on polling instead. Once Importapay documents
        // their scheme, plug it in here.
        if (webhookSecret == null || webhookSecret.isBlank()) return false;
        return WebhookSignature.verifyHmacSha512(rawBody, signature, webhookSecret);
    }

    @Override
    public List<BankOption> supportedBanks() {
        if (!enabled) return List.of();
        try {
            JsonNode banks = get("/merchant/banks").path("response");
            List<BankOption> out = new ArrayList<>();
            if (banks.isArray()) {
                banks.forEach(b -> out.add(new BankOption(
                        b.path("bankCode").asText(""),
                        b.path("bankName").asText(""))));
            }
            return out;
        } catch (RuntimeException ex) {
            log.warn("Importapay bank list fetch failed: {}", ex.getMessage());
            return List.of();
        }
    }

    // ----- internal ---------------------------------------------------------

    /** Map an Importapay status string to our internal PaymentIntent state. */
    private void applyStatus(PaymentIntent intent, String status, JsonNode response) {
        switch (status == null ? "" : status.toLowerCase()) {
            case "paid" -> {
                intent.setMatchedTransactionRef(
                        nullIfBlank(response.path("matchedTransactionReference").asText(null)));
                // Fees not returned in confirm/verify responses — leave at 0
                // until Importapay adds a fee field or we compute it from
                // the merchant balance API.
                intent.markSucceeded(0L, intent.getProviderReference());
            }
            case "failed" -> intent.markFailed("Transfer could not be matched");
            case "awaiting_confirmation", "pending" -> {
                // Leave status as PENDING; caller will re-poll verify.
            }
            case "ambiguous" -> intent.markFailed("Ambiguous match — multiple candidates or unclear refund");
            default -> log.info("Importapay: unknown status '{}' on intent {}", status, intent.getId());
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException(
                    "Importapay is not configured — set CONDDO_PAYMENTS_IMPORTAPAY_API_KEY");
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return restClient.post()
                .uri(path)
                .header("x-api-key", apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode get(String path) {
        return restClient.get()
                .uri(path)
                .header("x-api-key", apiKey)
                .retrieve()
                .body(JsonNode.class);
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
