package io.conddo.api.web.admin;

import io.conddo.api.web.FeatureFlagController;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.TenantFeatureFlag;
import io.conddo.core.service.TenantFeatureFlagService;
import io.conddo.core.service.TenantFeatureFlagService.FlagView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * SUPER_ADMIN-only feature flag grant / revoke surface (Pharmacy
 * Roadmap). The Conddo team reviews beta-access-request rows and
 * flips access for approved tenants here; revoke walks it back.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/feature-flags")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminFeatureFlagController {

    private final TenantFeatureFlagService service;

    public AdminFeatureFlagController(TenantFeatureFlagService service) {
        this.service = service;
    }

    @PostMapping("/{featureKey}/grant")
    public ApiResponse<Map<String, Object>> grant(@PathVariable UUID tenantId,
                                                  @PathVariable String featureKey,
                                                  @AuthenticationPrincipal Jwt jwt) {
        UUID grantedBy = UUID.fromString(jwt.getSubject());
        TenantFeatureFlag saved = service.grantAccess(tenantId, featureKey, grantedBy);
        return ApiResponse.ok(Map.of(
                "success", true,
                "flag", FeatureFlagController.toRow(new FlagView(
                        saved.getFeatureKey(), saved.getStatus(),
                        saved.isEnabled(), saved.isInterest(),
                        saved.getInterestAt(), saved.getGrantedAt(), saved.getGrantedBy()))));
    }

    @DeleteMapping("/{featureKey}/grant")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable UUID tenantId,
                                                   @PathVariable String featureKey) {
        TenantFeatureFlag saved = service.revokeAccess(tenantId, featureKey);
        return ApiResponse.ok(Map.of(
                "success", true,
                "flag", FeatureFlagController.toRow(new FlagView(
                        saved.getFeatureKey(), saved.getStatus(),
                        saved.isEnabled(), saved.isInterest(),
                        saved.getInterestAt(), saved.getGrantedAt(), saved.getGrantedBy()))));
    }
}
