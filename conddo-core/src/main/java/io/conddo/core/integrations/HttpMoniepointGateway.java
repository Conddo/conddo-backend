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
import java.util.Collections;
import java.util.List;

/**
 * Moniepoint POS API adapter (docs.pos.moniepoint.com).
 *
 * <p>Auth: {@code Authorization: Bearer <api key>}.
 *
 * <p>Key endpoints (from the official OpenAPI spec):
 * <ul>
 *   <li>{@code GET /v1/introspect} — verify the API key, returns scopes,
 *       businesses, and environment (SANDBOX/PROD).</li>
 *   <li>{@code POST /v1/transactions} — push a transaction to a terminal.</li>
 *   <li>{@code GET /v1/transactions/merchants/{merchantReference}} —
 *       look up a single transaction by the merchant-generated reference.</li>
 * </ul>
 *
 * <p>There is <b>no transaction search/list endpoint</b>. The money feed
 * comes exclusively from webhooks ({@code V1_POS_TRANSACTION} events).
 * {@link #fetchTransactions} returns an empty list; the poller is a no-op
 * for Moniepoint.
 */
@Component
public class HttpMoniepointGateway implements MoniepointGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpMoniepointGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final String webhookSecret;

    public HttpMoniepointGateway(
            @Value("${conddo.integrations.moniepoint.base-url:https://api.pos.moniepoint.com}") String baseUrl,
            @Value("${conddo.integrations.moniepoint.webhook-secret:}") String webhookSecret,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.webhookSecret = webhookSecret;
        log.info("MoniepointGateway active (base={})", baseUrl);
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public MerchantInfo verify(String apiKey) {
        try {
            // GET /v1/introspect — the real Moniepoint POS verify endpoint.
            // Returns: { scopes, businesses: [{ id, businessName }], authMethod, environment }
            JsonNode body = restClient.get()
                    .uri("/v1/introspect")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) {
                throw new VerificationException("Moniepoint returned empty response");
            }

            // If the response has an 'error' field, the key is invalid.
            if (body.has("error")) {
                throw new VerificationException(
                        "Moniepoint rejected the key: " + body.path("error").asText("invalid"));
            }

            String businessName = null;
            String terminalSerial = null; // introspect doesn't return terminals
            JsonNode businesses = body.path("businesses");
            if (businesses.isArray() && !businesses.isEmpty()) {
                JsonNode first = businesses.get(0);
                businessName = text(first, "businessName");
            }

            log.info("Moniepoint key verified: business={}, env={}",
                    businessName, text(body, "environment"));
            return new MerchantInfo(businessName, null, null, null, terminalSerial);
        } catch (VerificationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UnavailableException("Moniepoint verify failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<TransferRecord> fetchTransactions(String apiKey, OffsetDateTime since) {
        // The Moniepoint POS API has NO transaction search/list endpoint.
        // Transactions arrive via webhooks (V1_POS_TRANSACTION events).
        // Return empty — the poller is a no-op for Moniepoint.
        log.debug("Moniepoint fetchTransactions: no-op (webhook-only feed)");
        return Collections.emptyList();
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return WebhookSignature.verifyHmacSha512(rawBody, signature, webhookSecret);
    }

    // ----- helpers -----------------------------------------------------------

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }
}
