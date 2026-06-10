package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.TenantFeatureFlag;
import io.conddo.core.service.TenantFeatureFlagService;
import io.conddo.core.service.TenantFeatureFlagService.FlagView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped feature flag surface (Pharmacy Roadmap). Lists every
 * known feature with its current stage + the tenant's interaction
 * state, and captures interest / beta-access clicks.
 */
@RestController
@RequestMapping("/api/v1")
public class FeatureFlagController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";

    private final TenantFeatureFlagService service;

    public FeatureFlagController(TenantFeatureFlagService service) {
        this.service = service;
    }

    @GetMapping("/feature-flags")
    @PreAuthorize(READ)
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.listForCurrentTenant().stream()
                .map(FeatureFlagController::toRow)
                .toList());
    }

    @PostMapping("/feature-interest")
    @PreAuthorize(READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerInterest(
            @Valid @RequestBody FeatureKeyRequest body) {
        TenantFeatureFlag saved = service.registerInterest(body.featureKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of(
                "success", true,
                "message", "You're on the list.",
                "flag", toRow(toView(saved)))));
    }

    @PostMapping("/beta-access-request")
    @PreAuthorize(READ)
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestBetaAccess(
            @Valid @RequestBody FeatureKeyRequest body) {
        TenantFeatureFlag saved = service.requestBetaAccess(body.featureKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of(
                "success", true,
                "message", "Request received. We'll review and grant access shortly.",
                "flag", toRow(toView(saved)))));
    }

    private static FlagView toView(TenantFeatureFlag row) {
        return new FlagView(row.getFeatureKey(), row.getStatus(),
                row.isEnabled(), row.isInterest(),
                row.getInterestAt(), row.getGrantedAt(), row.getGrantedBy());
    }

    public static Map<String, Object> toRow(FlagView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("featureKey", view.featureKey());
        row.put("status", view.status());
        row.put("enabled", view.enabled());
        row.put("interest", view.interest());
        row.put("interestAt", view.interestAt());
        row.put("grantedAt", view.grantedAt());
        row.put("grantedBy", view.grantedBy());
        return row;
    }

    public record FeatureKeyRequest(@NotBlank String featureKey) {
    }
}
