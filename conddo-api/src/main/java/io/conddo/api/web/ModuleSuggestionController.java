package io.conddo.api.web;

import io.conddo.core.ai.AiCallContext;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.credits.CreditActions;
import io.conddo.core.service.ModuleSuggestionService;
import io.conddo.core.service.ModuleSuggestionService.Result;
import io.conddo.core.service.ModuleSuggestionService.Score;
import io.conddo.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI-driven module suggestion (Vertical Inference Phase C). Takes a
 * free-text business description and returns a ranked list of
 * modules the business would likely use. The FE renders this as a
 * preselected opt-in list during onboarding or when the tenant
 * later explores the catalogue.
 *
 * <p>Owner-only — modules affect billing surface area.
 */
@RestController
@RequestMapping("/api/v1/tenant/modules")
@PreAuthorize("@staffAccess.ownerOnly()")
public class ModuleSuggestionController {

    private final ModuleSuggestionService service;

    public ModuleSuggestionController(ModuleSuggestionService service) {
        this.service = service;
    }

    @PostMapping("/suggest")
    public ApiResponse<Map<String, Object>> suggest(@Valid @RequestBody SuggestRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        // Tenant-scoped classify — routes through AiGateway so the tenant
        // pays credits + must have verified their email. Uses
        // AI_PROVISIONING because "re-classify my modules from a new
        // description" is functionally the same action.
        AiCallContext ctx = AiCallContext.forTenant(
                TenantContext.require(),
                UUID.fromString(jwt.getSubject()),
                CreditActions.AI_PROVISIONING);
        Result result = service.suggest(ctx, body.businessDescription(), body.verticalHint());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("vertical", result.vertical());
        resp.put("verticalConfidence", result.verticalConfidence());
        resp.put("scores", result.scores().stream().map(ModuleSuggestionController::toRow).toList());
        resp.put("recommended", result.recommended().stream()
                .map(ModuleSuggestionController::toRow).toList());
        return ApiResponse.ok(resp);
    }

    private static Map<String, Object> toRow(Score s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.id());
        row.put("confidence", s.confidence());
        row.put("reason", s.reason());
        return row;
    }

    public record SuggestRequest(@NotBlank String businessDescription, String verticalHint) {
    }
}
