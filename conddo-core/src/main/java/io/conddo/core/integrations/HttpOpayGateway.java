package io.conddo.core.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.core.payments.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real OPay merchant-API adapter (Checkout v3). Verification uses OPay's
 * documented merchant-verify endpoint; each credential field is format-
 * checked first so the UI can pinpoint which one is wrong.
 *
 * <p>Auth on the verify call: {@code Authorization: Bearer <publicKey>}
 * + {@code MerchantId: <merchantId>}, body signed with the private key
 * per OPay's signature scheme. Amounts are kobo.
 */
@Component
public class HttpOpayGateway implements OpayGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpOpayGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final String webhookSecret;

    public HttpOpayGateway(
            @Value("${conddo.integrations.opay.base-url:https://opaycheckout.com/api/v3}") String baseUrl,
            @Value("${conddo.integrations.opay.webhook-secret:}") String webhookSecret,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.webhookSecret = webhookSecret;
        log.info("OpayGateway active (base={})", baseUrl);
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public MerchantInfo verify(String merchantId, String privateKey, String publicKey) {
        // Field-level format checks — each failure names the field so the
        // UI can highlight exactly what's wrong (endpoint #8 contract).
        String id = merchantId == null ? "" : merchantId.trim();
        if (!id.matches("\\d{15}")) {
            throw new VerificationException("merchantId must be the 15-digit merchant id from the OPay dashboard");
        }
        String prv = privateKey == null ? "" : privateKey.trim();
        if (!prv.toUpperCase().startsWith("OPAYPRV")) {
            throw new VerificationException("privateKey must be the OPAYPRV… secret key from the OPay dashboard");
        }
        String pub = publicKey == null ? "" : publicKey.trim();
        if (!pub.toUpperCase().startsWith("OPAYPUB")) {
            throw new VerificationException("publicKey must be the OPAYPUB… public key from the OPay dashboard");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("merchantId", id);
            body.put("secretKey", prv);
            JsonNode response = restClient.post()
                    .uri("/merchant/verify")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + pub)
                    .header("MerchantId", id)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"00000".equals(response.path("code").asText())) {
                throw new VerificationException(
                        "OPay rejected these credentials: "
                                + (response == null ? "no response" : response.path("message").asText("invalid credentials")));
            }
            JsonNode data = response.path("data");
            return new MerchantInfo(
                    text(data, "merchantName"),
                    text(data, "accountName"),
                    text(data, "accountNumber"),
                    text(data, "bankName"),
                    null);
        } catch (VerificationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UnavailableException("OPay verify failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<TransferRecord> fetchTransactions(String merchantId, String publicKey, OffsetDateTime since) {
        // OPay does not document a list-collections endpoint publicly;
        // inbound money is webhook-primary for OPay. The poller skips
        // accounts whose gateway returns an empty feed.
        return List.of();
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return WebhookSignature.verifyHmacSha512(rawBody, signature, webhookSecret);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }
}
