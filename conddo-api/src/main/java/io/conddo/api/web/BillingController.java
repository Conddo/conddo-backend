package io.conddo.api.web;

import io.conddo.api.web.dto.PlanDto;
import io.conddo.api.web.dto.SubscriptionDto;
import io.conddo.api.web.dto.UpgradeRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.service.BillingPaystackService;
import io.conddo.core.service.BillingPaystackService.CheckoutResult;
import io.conddo.core.service.BillingPaystackService.VerifyResult;
import io.conddo.core.service.BillingService;
import io.conddo.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Billing endpoints (BILLING_TIERS_SPEC §4). The catalog is public-ish —
 * available to any authenticated user (the FE pricing page can also call it
 * via a server-side request). Subscription read/write is TENANT_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private static final String ADMIN_ONLY = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";
    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";

    private final BillingService billingService;
    private final BillingPaystackService paystackService;

    public BillingController(BillingService billingService,
                              BillingPaystackService paystackService) {
        this.billingService = billingService;
        this.paystackService = paystackService;
    }

    @GetMapping("/plans")
    @PreAuthorize(READ)
    public ApiResponse<List<PlanDto>> plans() {
        return ApiResponse.ok(billingService.catalog().stream().map(PlanDto::from).toList());
    }

    @GetMapping("/subscription")
    @PreAuthorize(READ)
    public ApiResponse<SubscriptionDto> subscription() {
        UUID tenantId = TenantContext.require();
        return ApiResponse.ok(billingService.getActiveSubscription(tenantId)
                .map(SubscriptionDto::from)
                .orElseThrow(() -> new NotFoundException("No active subscription for this tenant")));
    }

    @PostMapping("/upgrade")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<SubscriptionDto> upgrade(@Valid @RequestBody UpgradeRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        // Gate the Student tier at the upgrade path too — otherwise a tenant
        // could sign up on Starter with a personal email and then switch down
        // to Student. Read the caller's email from the JWT; falling back to
        // the token's subject is the equivalent identity handle in the
        // TENANT_ADMIN flow.
        if (io.conddo.core.billing.StudentEligibility.isStudentPlan(request.planId())) {
            String callerEmail = jwt != null ? jwt.getClaimAsString("email") : null;
            io.conddo.core.billing.StudentEligibility.assertEligible(callerEmail);
        }
        billingService.upgrade(tenantId, request.planId(), request.billingCycle());
        // Reread with the plan for the response.
        return ApiResponse.ok(SubscriptionDto.from(
                billingService.getActiveSubscription(tenantId).orElseThrow()));
    }

    @PostMapping("/cancel")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<SubscriptionDto> cancel() {
        UUID tenantId = TenantContext.require();
        paystackService.cancel();
        return ApiResponse.ok(SubscriptionDto.from(
                billingService.getActiveSubscription(tenantId).orElseThrow()));
    }

    /**
     * Initialise a Paystack hosted checkout for a plan upgrade
     * (HANDOFF_2026-06-11 §8). FE redirects the customer to the
     * returned {@code authorizationUrl}.
     */
    @PostMapping("/checkout")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<Map<String, Object>> checkout(@Valid @RequestBody CheckoutRequest body,
                                                      @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        CheckoutResult result = paystackService.checkout(body.planId(), body.billingCycle(), userId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("authorizationUrl", result.authorizationUrl());
        row.put("reference", result.reference());
        row.put("accessCode", result.accessCode());
        return ApiResponse.ok(row);
    }

    /**
     * Verify a Paystack transaction. The FE return page polls this
     * (3s for 60s, then 10s up to attempt 40) waiting for either a
     * terminal status or the webhook to flip the row.
     */
    @GetMapping("/verify")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<Map<String, Object>> verify(@RequestParam("reference") String reference) {
        VerifyResult result = paystackService.verify(reference);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", result.transaction().getStatus());
        row.put("reference", result.transaction().getReference());
        row.put("amount", result.transaction().getAmountKobo() / 100);
        row.put("paidAt", result.transaction().getPaidAt());
        row.put("failureReason", result.transaction().getFailureReason());
        if (result.subscription() != null) {
            row.put("subscription", SubscriptionDto.from(
                    billingService.getActiveSubscription(result.subscription().getTenantId()).orElseThrow()));
        }
        return ApiResponse.ok(row);
    }

    public record CheckoutRequest(@NotBlank String planId, String billingCycle) {
    }
}
