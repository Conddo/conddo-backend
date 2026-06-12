package io.conddo.studio.platform;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.platform.PlatformFeatureFlagService.Row;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Studio cross-tenant feature-flag review queue
 * (HANDOFF_2026-06-12b §2). FE at
 * {@code /admin/platform/feature-flags} is wired to this.
 *
 * <ul>
 *   <li>{@code GET} — list, gated to ADMIN + TEAM_LEAD (read).</li>
 *   <li>{@code POST .../grant}, {@code POST .../revoke} — ADMIN only.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jobs/admin/platform/feature-flags")
@PreAuthorize("hasAnyRole('ADMIN','TEAM_LEAD')")
public class PlatformFeatureFlagController {

    private final PlatformFeatureFlagService service;

    public PlatformFeatureFlagController(PlatformFeatureFlagService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "featureKey", required = false) String featureKey) {
        return ApiResponse.ok(service.list(status, featureKey).stream()
                .map(Row::toMap)
                .toList());
    }

    @PostMapping("/{tenantId}/{featureKey}/grant")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> grant(@PathVariable UUID tenantId,
                                                   @PathVariable String featureKey,
                                                   @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = StudioPrincipal.staffId(jwt);
        return ApiResponse.ok(service.grant(tenantId, featureKey, actorId).toMap());
    }

    @PostMapping("/{tenantId}/{featureKey}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable UUID tenantId,
                                                    @PathVariable String featureKey) {
        return ApiResponse.ok(service.revoke(tenantId, featureKey).toMap());
    }
}
