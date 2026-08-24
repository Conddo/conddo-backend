package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.IntegrationService;
import io.conddo.core.service.IntegrationService.IntegrationView;
import io.conddo.core.service.IntegrationService.Overview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-side connected-accounts endpoints (Priority 3 items 7-9).
 *
 * <p>Backs the mobile app's "Connected Accounts" screen:
 * <ul>
 *   <li>{@code GET /integrations} — list connected accounts + money-feed stats
 *   <li>{@code POST /integrations/moniepoint} — store + verify Moniepoint key
 *   <li>{@code POST /integrations/opay} — store + verify OPay credentials
 *   <li>{@code DELETE /integrations/{provider}} — soft disconnect
 * </ul>
 *
 * <p>Credentials are encrypted at rest inside {@link IntegrationService}
 * and are NEVER returned to the client. Each provider is verified live
 * during the connect call so an invalid key fails immediately with a
 * useful error the mobile UI can render inline.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {

    private final IntegrationService service;

    public IntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Overview> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/moniepoint")
    public ApiResponse<IntegrationView> connectMoniepoint(@Valid @RequestBody MoniepointRequest req) {
        return ApiResponse.ok(service.connectMoniepoint(req.apiKey()));
    }

    @PostMapping("/opay")
    public ApiResponse<IntegrationView> connectOpay(@Valid @RequestBody OpayRequest req) {
        return ApiResponse.ok(service.connectOpay(req.merchantId(), req.privateKey(), req.publicKey()));
    }

    @PostMapping("/paystack")
    public ApiResponse<IntegrationView> connectPaystack(@Valid @RequestBody PaystackRequest req) {
        return ApiResponse.ok(service.connectPaystack(req.secretKey()));
    }

    @DeleteMapping("/{provider}")
    public ApiResponse<IntegrationView> disconnect(@PathVariable String provider) {
        return ApiResponse.ok(service.disconnect(provider));
    }

    // ----- wire shape ------------------------------------------------------

    public record MoniepointRequest(@NotBlank String apiKey) {}

    /** All three OPay fields required — matches the tenant dashboard's per-field
     *  copy-paste flow. Blank fields fail validation before we hit the provider. */
    public record OpayRequest(
            @NotBlank String merchantId,
            @NotBlank String privateKey,
            @NotBlank String publicKey) {}

    public record PaystackRequest(@NotBlank String secretKey) {}
}
