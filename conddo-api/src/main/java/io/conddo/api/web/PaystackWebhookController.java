package io.conddo.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.paystack.PaystackGateway;
import io.conddo.core.service.BillingPaystackService;
import io.conddo.core.service.BillingPaystackService.JsonLike;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paystack webhook receiver (HANDOFF_2026-06-11 §8). No auth — the
 * security model is HMAC-SHA512 signature verification against the
 * shared secret. Signature mismatches return 401 silently (no body)
 * so a probing attacker doesn't learn anything from the response.
 */
@RestController
@RequestMapping("/api/v1/billing/webhooks/paystack")
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);

    private final PaystackGateway gateway;
    private final BillingPaystackService service;
    private final ObjectMapper objectMapper;

    public PaystackWebhookController(PaystackGateway gateway,
                                     BillingPaystackService service,
                                     ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                        HttpServletRequest request) {
        String signature = request.getHeader("x-paystack-signature");
        if (!gateway.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Paystack webhook rejected: signature mismatch");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            JsonNode tree = objectMapper.readTree(rawBody);
            String eventType = tree.path("event").asText("");
            service.handleWebhook(eventType, new JacksonJsonLike(tree));
            return ResponseEntity.ok().build();
        } catch (RuntimeException | java.io.IOException ex) {
            log.error("Paystack webhook handling failed: {}", ex.getMessage());
            // Return 200 anyway — Paystack retries 5xx and we don't
            // want a parse glitch to cause spam. The event will be
            // re-reconciled by the FE's /verify polling on the next
            // tenant action.
            return ResponseEntity.ok().build();
        }
    }

    private record JacksonJsonLike(JsonNode root) implements JsonLike {
        @Override
        public String string(String dottedPath) {
            JsonNode node = root;
            for (String part : dottedPath.split("\\.")) {
                node = node.path(part);
            }
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.asText();
        }
    }
}
