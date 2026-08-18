package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.IntegrationService;
import io.conddo.core.service.IntegrationService.IntegrationView;
import io.conddo.core.service.IntegrationService.Overview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connected accounts (Moniepoint / OPay). POST /moniepoint stores + verifies
 * the tenant's Moniepoint key ({@code mp_live_…}) and returns the terminal /
 * business snapshot; POST /opay does the same for the OPay credential
 * triple, validating each field separately. GET lists the accounts with
 * "sales brought in / waiting to match" stats.
 *
 * <p>Credentials are encrypted at rest and never appear on the wire — the
 * {@link IntegrationView} deliberately excludes them.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {

    private static final String READ = "@staffAccess.canRead('payments')";
    private static final String WRITE = "@staffAccess.ownerOnly()";

    private final IntegrationService service;

    public IntegrationController(IntegrationService service) {
        this.service = service;
    }

    /** Connected accounts + money-feed stats. */
    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<Overview> list() {
        return ApiResponse.ok(service.list());
    }

    /** Store + verify a Moniepoint merchant key. Returns the live account row. */
    @PostMapping("/moniepoint")
    @PreAuthorize(WRITE)
    public ApiResponse<IntegrationView> connectMoniepoint(@Valid @RequestBody MoniepointRequest body) {
        return ApiResponse.ok(service.connectMoniepoint(body.apiKey()));
    }

    /** Store + verify OPay merchant credentials (field-by-field). */
    @PostMapping("/opay")
    @PreAuthorize(WRITE)
    public ApiResponse<IntegrationView> connectOpay(@Valid @RequestBody OpayRequest body) {
        return ApiResponse.ok(service.connectOpay(
                body.merchantId(), body.privateKey(), body.publicKey()));
    }

    /** Soft-disconnect an account (credentials stay for a future reconnect). */
    @DeleteMapping("/{provider}")
    @PreAuthorize(WRITE)
    public ApiResponse<IntegrationView> disconnect(@PathVariable String provider) {
        return ApiResponse.ok(service.disconnect(provider));
    }

    public record MoniepointRequest(@NotBlank String apiKey) {
    }

    public record OpayRequest(
            @NotBlank String merchantId,
            @NotBlank String privateKey,
            @NotBlank String publicKey) {
    }
}
