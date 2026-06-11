package io.conddo.core.paystack;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real Paystack adapter (HANDOFF_2026-06-11 §8). Active only when
 * {@code CONDDO_PAYSTACK_SECRET_KEY} is set, replacing
 * {@link DormantPaystackGateway} as {@code @Primary}.
 *
 * <p>Wraps the three real API calls (transaction init, transaction
 * verify, subscription disable) + HMAC-SHA512 webhook signature
 * verification. Anything else (refunds, plan creation, sub-account
 * routing) lands when we need it.
 */
@Component
@Primary
@ConditionalOnExpression("'${conddo.paystack.secret-key:}' != ''")
public class HttpPaystackGateway implements PaystackGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaystackGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final String secretKey;

    public HttpPaystackGateway(
            @Value("${conddo.paystack.base-url:https://api.paystack.co}") String baseUrl,
            @Value("${conddo.paystack.secret-key:}") String secretKey,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.secretKey = secretKey;
        log.info("PaystackGateway active (base={})", baseUrl);
    }

    @Override
    public InitResult initialize(String email, long amountKobo, String reference,
                                 String callbackUrl, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("amount", amountKobo);
        body.put("reference", reference);
        if (callbackUrl != null) {
            body.put("callback_url", callbackUrl);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        try {
            JsonNode response = post("/transaction/initialize", body);
            JsonNode data = response.path("data");
            return new InitResult(
                    data.path("authorization_url").asText(null),
                    data.path("reference").asText(reference),
                    data.path("access_code").asText(null));
        } catch (PaystackUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaystackUnavailableException(
                    "Paystack initialize failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public VerifyResult verify(String reference) {
        try {
            JsonNode response = get("/transaction/verify/" + reference);
            JsonNode data = response.path("data");
            String statusRaw = data.path("status").asText("pending").toLowerCase();
            VerifyResult.Status status = switch (statusRaw) {
                case "success" -> VerifyResult.Status.SUCCESS;
                case "failed" -> VerifyResult.Status.FAILED;
                case "abandoned" -> VerifyResult.Status.ABANDONED;
                default -> VerifyResult.Status.PENDING;
            };
            BigDecimal amountNgn = BigDecimal.valueOf(data.path("amount").asLong(0))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            OffsetDateTime paidAt = null;
            if (!data.path("paid_at").isMissingNode() && !data.path("paid_at").isNull()) {
                try {
                    paidAt = OffsetDateTime.parse(data.path("paid_at").asText());
                } catch (RuntimeException ignored) {
                    // Paystack timestamp format quirks — leave null and rely on the webhook.
                }
            }
            return new VerifyResult(
                    data.path("reference").asText(reference),
                    status,
                    amountNgn,
                    paidAt,
                    nullIfBlank(data.path("gateway_response").asText(null)),
                    data.path("authorization").path("authorization_code").asText(null),
                    data.path("customer").path("customer_code").asText(null));
        } catch (PaystackUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaystackUnavailableException(
                    "Paystack verify failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void disableSubscription(String subscriptionCode, String emailToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", subscriptionCode);
        if (emailToken != null) {
            body.put("token", emailToken);
        }
        try {
            post("/subscription/disable", body);
        } catch (RuntimeException ex) {
            throw new PaystackUnavailableException(
                    "Paystack subscription disable failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String body, String signature) {
        if (body == null || signature == null || signature.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(signature);
        } catch (RuntimeException | java.security.GeneralSecurityException ex) {
            log.warn("Paystack signature verification failed: {}", ex.getMessage());
            return false;
        }
    }

    // ----- private RestClient wrappers ---------------------------------------

    private JsonNode post(String path, Map<String, Object> body) {
        return restClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode get(String path) {
        return restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .retrieve()
                .body(JsonNode.class);
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
