package io.conddo.studio.platform;

import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.web.dto.PlatformTenantDto;
import io.conddo.studio.web.dto.PlatformUserDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public PlatformAdminController(PlatformAdminService service) {
        this.service = service;
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
}
