package io.conddo.api.web;

import io.conddo.api.web.dto.MeResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.credits.CreditService;
import io.conddo.core.service.MeService;
import io.conddo.core.tenant.TenantContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /api/v1/me} — the current user + tenant for the dashboard shell.
 * Also exposes the tenant's credit summary at {@code /credits} for the
 * home-screen credits widget.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;
    private final CreditService creditService;

    public MeController(MeService meService, CreditService creditService) {
        this.meService = meService;
        this.creditService = creditService;
    }

    @GetMapping
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        MeService.Identity identity = meService.current(UUID.fromString(jwt.getSubject()));
        return ApiResponse.ok(MeResponse.from(identity.user(), identity.tenant()));
    }

    @GetMapping("/credits")
    public ApiResponse<CreditService.Summary> credits() {
        // Tenant bound by the security filter; RLS + the service handle the rest.
        return ApiResponse.ok(creditService.summaryFor(TenantContext.require()));
    }
}
