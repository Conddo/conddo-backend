package io.conddo.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.TenantIntegration;
import io.conddo.core.integrations.MoniepointGateway;
import io.conddo.core.integrations.OpayGateway;
import io.conddo.core.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook receiver for connected-account providers. One endpoint per
 * provider: {@code POST /api/v1/webhooks/integrations/{provider}} with
 * {@code provider} ∈ {@code moniepoint | opay}. No auth — the security
 * model is HMAC-SHA512 signature verification (Moniepoint stamps
 * {@code monnify-signature}; OPay {@code x-opay-signature} / {@code
 * signature}). Mismatches return 401 silently.
 *
 * <p>Payloads are routed to the owning tenant via the merchant reference
 * they carry (OPay merchantId, or the hash of the Moniepoint api key)
 * and pushed into {@code /transfers/incoming}, deduped on
 * (provider, providerReference). The poller in {@code TransferService}
 * is the always-on fallback when webhooks aren't configured.
 */
@RestController
@RequestMapping("/api/v1/webhooks/integrations")
public class IntegrationWebhookController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationWebhookController.class);

    private final MoniepointGateway moniepointGateway;
    private final OpayGateway opayGateway;
    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    public IntegrationWebhookController(MoniepointGateway moniepointGateway,
                                        OpayGateway opayGateway,
                                        TransferService transferService,
                                        ObjectMapper objectMapper) {
        this.moniepointGateway = moniepointGateway;
        this.opayGateway = opayGateway;
        this.transferService = transferService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestBody String rawBody,
                                        HttpServletRequest request) {
        if (TenantIntegration.PROVIDER_MONIEPOINT.equals(provider)) {
            String signature = request.getHeader("monnify-signature");
            if (!moniepointGateway.verifyWebhookSignature(rawBody, signature)) {
                log.warn("Moniepoint webhook rejected: signature mismatch");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } else if (TenantIntegration.PROVIDER_OPAY.equals(provider)) {
            String signature = request.getHeader("x-opay-signature");
            if (signature == null || signature.isBlank()) {
                signature = request.getHeader("signature");
            }
            if (!opayGateway.verifyWebhookSignature(rawBody, signature)) {
                log.warn("OPay webhook rejected: signature mismatch");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } else {
            log.warn("integration webhook: unknown provider {}", provider);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            JsonNode tree = objectMapper.readTree(rawBody);
            boolean created = transferService.ingestWebhook(provider, rawBody, tree);
            if (created) {
                log.info("{} webhook: ingested transfer into the feed", provider);
            }
            // Always ack 200: a parse/routing miss is logged above and a
            // retry would just re-hit the same dedupe. Provider retry
            // storms on 5xx buy us nothing.
            return ResponseEntity.ok().build();
        } catch (RuntimeException | java.io.IOException ex) {
            log.error("{} webhook handling failed: {}", provider, ex.getMessage());
            return ResponseEntity.ok().build();
        }
    }
}
