package io.conddo.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.service.SocialMarketingService;
import io.conddo.core.social.AyrshareWebhookVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public webhook endpoint for Ayrshare delivery / disconnect events
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §2). Unauthenticated — verified by
 * HMAC-SHA256 over the raw body using the shared secret in
 * {@code AYRSHARE_WEBHOOK_SECRET}. Replay isn't a concern (each Ayrshare
 * event is idempotent on our side: the reconcile just re-stamps the row).
 *
 * <p>Configured in {@code AyrshareWebhookSecurityConfig} as a public
 * filter-chain rule — no JWT, no tenant context.
 */
@RestController
@RequestMapping("/webhooks/ayrshare")
public class AyrshareWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AyrshareWebhookController.class);
    private static final String SIGNATURE_HEADER_DEFAULT = "X-Ayrshare-Signature";

    private final SocialMarketingService service;
    private final AyrshareWebhookVerifier verifier;
    private final ObjectMapper objectMapper;

    public AyrshareWebhookController(SocialMarketingService service,
                                     AyrshareWebhookVerifier verifier,
                                     ObjectMapper objectMapper) {
        this.service = service;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> handle(HttpServletRequest request,
                                       @RequestHeader(value = SIGNATURE_HEADER_DEFAULT, required = false)
                                               String signature) throws Exception {
        if (!verifier.isConfigured()) {
            log.warn("Ayrshare webhook arrived but AYRSHARE_WEBHOOK_SECRET is not configured — rejecting.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        byte[] raw = request.getInputStream().readAllBytes();
        if (!verifier.verify(raw, signature)) {
            log.warn("Ayrshare webhook rejected — bad HMAC signature.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode payload = objectMapper.readTree(raw);
        String eventType = payload.path("action").asText(
                payload.path("type").asText(payload.path("event").asText(null)));
        String ayrsharePostId = payload.path("id").asText(
                payload.path("postId").asText(null));
        String provider = payload.path("platform").asText(null);
        String externalPostId = payload.path("postUrl").asText(
                payload.path("externalId").asText(null));
        String errorMessage = payload.path("error").asText(null);

        try {
            service.applyWebhook(eventType, ayrsharePostId, provider, externalPostId, errorMessage);
        } catch (RuntimeException ex) {
            // Ayrshare will retry — return 5xx so they back off and try again
            // rather than treat the delivery as completed.
            log.error("Ayrshare webhook handling failed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }
}
