package io.conddo.studio.platform;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.web.dto.PlatformTenantDto;
import io.conddo.studio.web.dto.PlatformUserDto;
import io.conddo.studio.web.dto.UpdatePlatformTenantRequest;
import io.conddo.studio.web.dto.UpdatePlatformUserRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Studio Platform Admin — read-only (Infrastructure §23, Phase 13a). Lets
 * a Studio ADMIN search and inspect tenants + users across the whole platform
 * from one console. Mutations land in Phase 13b — this slice ships the
 * surface the FE listed under §11.13 as the next dependency.
 *
 * <p>ADMIN-only on every endpoint (not TEAM_LEAD): the blast radius — every
 * tenant, every user — is large enough that we want the read-only view gated
 * to the same role that will own the mutators in 13b.
 */
@RestController
@RequestMapping("/api/jobs/admin/platform")
@PreAuthorize("hasRole('ADMIN')")
public class PlatformAdminController {

    /** Hard cap on page size — sane default for the FE's list views. */
    private static final int MAX_SIZE = 100;

    private final PlatformAdminService service;
    private final PlatformAdminMutationService mutations;

    public PlatformAdminController(PlatformAdminService service,
                                   PlatformAdminMutationService mutations) {
        this.service = service;
        this.mutations = mutations;
    }

    // ----- tenants ------------------------------------------------------------

    @GetMapping("/tenants")
    public ApiResponse<List<PlatformTenantDto>> listTenants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<PlatformTenant> result = service.searchTenants(q, status,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_SIZE)));
        return ApiResponse.ok(
                result.getContent().stream().map(PlatformTenantDto::summary).toList(),
                ApiResponse.Meta.page(result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<PlatformTenantDto> getTenant(@PathVariable UUID tenantId) {
        return ApiResponse.ok(PlatformTenantDto.detail(service.getTenant(tenantId)));
    }

    @GetMapping("/tenants/{tenantId}/users")
    public ApiResponse<List<PlatformUserDto>> usersForTenant(@PathVariable UUID tenantId) {
        return ApiResponse.ok(service.listUsersForTenant(tenantId).stream()
                .map(PlatformUserDto::summary).toList());
    }

    // ----- users --------------------------------------------------------------

    @GetMapping("/users")
    public ApiResponse<List<PlatformUserDto>> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<PlatformUser> result = service.searchUsers(q, tenantId, role,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_SIZE)));
        return ApiResponse.ok(
                result.getContent().stream().map(PlatformUserDto::summary).toList(),
                ApiResponse.Meta.page(result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<PlatformUserDto> getUser(@PathVariable UUID userId) {
        return ApiResponse.ok(PlatformUserDto.detail(service.getUser(userId)));
    }

    // ----- mutations (§23 Phase 13b) -----------------------------------------

    /**
     * Update tenant fields. {@code status} accepts {@code ACTIVE} or
     * {@code SUSPENDED}; setting to {@code SUSPENDED} revokes every
     * refresh-token family on that tenant. To soft-delete use the DELETE.
     */
    @PatchMapping("/tenants/{tenantId}")
    public ApiResponse<PlatformTenantDto> patchTenant(@PathVariable UUID tenantId,
                                                      @Valid @RequestBody UpdatePlatformTenantRequest body,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PlatformTenantDto.summary(
                mutations.patchTenant(StudioPrincipal.staffId(jwt),
                        tenantId, body.name(), body.planId(), body.status())));
    }

    /** Soft-delete the tenant — flips status to {@code DELETED}, revokes sessions. */
    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<Void> deleteTenant(@PathVariable UUID tenantId,
                                             @AuthenticationPrincipal Jwt jwt) {
        mutations.softDeleteTenant(StudioPrincipal.staffId(jwt), tenantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update user role / active flag / display name. Refuses to demote or
     * deactivate the last active TENANT_ADMIN of a tenant
     * ({@code 422 LAST_ADMIN_PROTECTED}); deactivating or role-changing
     * revokes the user's refresh-token family.
     */
    @PatchMapping("/users/{userId}")
    public ApiResponse<PlatformUserDto> patchUser(@PathVariable UUID userId,
                                                  @Valid @RequestBody UpdatePlatformUserRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PlatformUserDto.summary(
                mutations.patchUser(StudioPrincipal.staffId(jwt),
                        userId, body.role(), body.active(), body.fullName())));
    }

    /** Triggers the standard password-reset email via the platform's /auth/forgot-password. */
    @PostMapping("/users/{userId}/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetUserPassword(@PathVariable UUID userId,
                                                               @AuthenticationPrincipal Jwt jwt) {
        boolean accepted = mutations.requestPasswordReset(StudioPrincipal.staffId(jwt), userId);
        if (!accepted) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.fail(io.conddo.studio.common.ApiError.of(
                            "PLATFORM_API_UNAVAILABLE",
                            "Studio cannot reach the platform's password-reset endpoint")));
        }
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }

    /** Soft-delete a user — deactivate + revoke sessions. Subject to last-admin protection. */
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID userId,
                                           @AuthenticationPrincipal Jwt jwt) {
        mutations.softDeleteUser(StudioPrincipal.staffId(jwt), userId);
        return ResponseEntity.noContent().build();
    }
}
