package io.conddo.api.web;

import io.conddo.api.web.dto.MeResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.credits.CreditService;
import io.conddo.core.domain.DailyBrief;
import io.conddo.core.service.DailyBriefService;
import io.conddo.core.service.MeService;
import io.conddo.core.tenant.TenantContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/v1/me} — the current user + tenant for the dashboard shell.
 * Also exposes the tenant's credit summary at {@code /credits} and the AI
 * Daily Business Brief at {@code /brief} — the home-screen widgets.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;
    private final CreditService creditService;
    private final DailyBriefService dailyBriefService;

    public MeController(MeService meService, CreditService creditService,
                        DailyBriefService dailyBriefService) {
        this.meService = meService;
        this.creditService = creditService;
        this.dailyBriefService = dailyBriefService;
    }

    @GetMapping
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        MeService.Identity identity = meService.current(UUID.fromString(jwt.getSubject()));
        return ApiResponse.ok(MeResponse.from(identity.user(), identity.tenant()));
    }

    @GetMapping("/credits")
    public ApiResponse<CreditService.Summary> credits() {
        return ApiResponse.ok(creditService.summaryFor(TenantContext.require()));
    }

    /**
     * Today's Daily Business Brief for the current tenant. Cached in
     * {@code daily_briefs} for 12h so a second dashboard open reuses it.
     * Free (no credit charge) — platform overhead per the Billing spec.
     * Verified-email gate: returns a "verify to unlock" placeholder for
     * unverified accounts instead of firing the LLM call.
     */
    @GetMapping("/brief")
    public ApiResponse<Map<String, Object>> brief(@AuthenticationPrincipal Jwt jwt) {
        MeService.Identity identity = meService.current(UUID.fromString(jwt.getSubject()));
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!identity.user().isEmailVerified()) {
            resp.put("state", "verify-email");
            resp.put("headline", "Verify your email to unlock your daily brief");
            resp.put("body", "Once you click the link we sent, a fresh morning briefing will "
                    + "appear here every day — your revenue, what needs attention, who to follow up with.");
            return ApiResponse.ok(resp);
        }
        DailyBrief brief = dailyBriefService.todayFor(TenantContext.require());
        resp.put("state", "ready");
        resp.put("headline", brief.getHeadline());
        resp.put("body", brief.getBody());
        resp.put("generatedAt", brief.getGeneratedAt() != null ? brief.getGeneratedAt().toString() : null);
        return ApiResponse.ok(resp);
    }
}
