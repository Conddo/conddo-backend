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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Moniepoint merchant-API adapter. Active whenever a base URL is
 * configured (always — the tenant key is passed per call, so there is no
 * platform key to gate on).
 *
 * <p>Auth: {@code Authorization: Bearer <merchant api key>}. Amounts from
 * the Monnify rails are in kobo (the smallest currency unit) — they are
 * stored as-is, matching the rest of the codebase.
 */
@Component
public class HttpMoniepointGateway implements MoniepointGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpMoniepointGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final RestClient restClient;
    private final String webhookSecret;

    public HttpMoniepointGateway(
            @Value("${conddo.integrations.moniepoint.base-url:https://pos.moniepoint.com}") String baseUrl,
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
            JsonNode body = search(apiKey, 0, 1, null, null);
            if (!body.path("requestSuccessful").asBoolean(false)) {
                throw new VerificationException(
                        "Moniepoint rejected the key: " + body.path("responseMessage").asText("invalid key"));
            }
            JsonNode content = body.path("responseBody").path("content");
            String businessName = null;
            String terminalSerial = null;
            if (content.isArray() && !content.isEmpty()) {
                JsonNode first = content.get(0);
                businessName = text(first, "merchantName");
                terminalSerial = text(first, "terminalSerialNumber");
            }
            return new MerchantInfo(businessName, null, null, null, terminalSerial);
        } catch (VerificationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UnavailableException("Moniepoint verify failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<TransferRecord> fetchTransactions(String apiKey, OffsetDateTime since) {
        List<TransferRecord> out = new ArrayList<>();
        int page = 0;
        int size = 100;
        while (true) {
            String from = since == null ? null : DATE.format(since.toLocalDate());
            String to = DATE.format(OffsetDateTime.now().toLocalDate());
            JsonNode body = search(apiKey, page, size, from, to);
            if (!body.path("requestSuccessful").asBoolean(false)) {
                throw new UnavailableException(
                        "Moniepoint search failed: " + body.path("responseMessage").asText("unknown"),
                        null);
            }
            JsonNode content = body.path("responseBody").path("content");
            if (!content.isArray() || content.isEmpty()) {
                break;
            }
            for (JsonNode row : content) {
                TransferRecord rec = toTransferRecord(row);
                if (rec != null) {
                    out.add(rec);
                }
            }
            int totalPages = body.path("responseBody").path("totalPages").asInt(0);
            page++;
            if (page >= totalPages) {
                break;
            }
        }
        return out;
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return WebhookSignature.verifyHmacSha512(rawBody, signature, webhookSecret);
    }

    // ----- internals ---------------------------------------------------------

    private JsonNode search(String apiKey, int page, int size, String from, String to) {
        StringBuilder uri = new StringBuilder("/api/v1/transactions/search")
                .append("?page=").append(page)
                .append("&size=").append(size)
                .append("&paymentStatus=PAID");
        if (from != null) {
            uri.append("&from=").append(from);
        }
        if (to != null) {
            uri.append("&to=").append(to);
        }
        return restClient.get()
                .uri(uri.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Map one Monnify transaction row to our feed shape. Amount is kobo. */
    private TransferRecord toTransferRecord(JsonNode row) {
        String reference = text(row, "transactionReference");
        if (reference == null) {
            reference = text(row, "paymentReference");
        }
        if (reference == null || !"PAID".equalsIgnoreCase(text(row, "paymentStatus"))) {
            return null;
        }
        OffsetDateTime receivedAt = parseInstant(text(row, "paidOn"));
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now();
        }
        return new TransferRecord(
                reference,
                firstNonNull(text(row, "customerName"), text(row, "payerName")),
                firstNonNull(text(row, "customerAccountNumber"), text(row, "payerAccountNumber")),
                firstNonNull(text(row, "customerBank"), text(row, "payerBank")),
                row.path("amount").asLong(0),
                firstNonNull(text(row, "currencyCode"), "NGN"),
                receivedAt,
                row.toString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static OffsetDateTime parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
