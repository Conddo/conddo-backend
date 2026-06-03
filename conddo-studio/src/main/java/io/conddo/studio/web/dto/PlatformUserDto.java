package io.conddo.studio.web.dto;

import io.conddo.studio.platform.PlatformAdminService;
import io.conddo.studio.platform.PlatformUser;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape for the platform-admin user endpoints (§23.3). The detail view
 * also carries a minimal tenant summary so the FE doesn't have to round-trip
 * back to {@code /platform/tenants/{id}} for the breadcrumb.
 */
public record PlatformUserDto(
        UUID id,
        UUID tenantId,
        String email,
        String fullName,
        String role,
        String phone,
        boolean active,
        boolean phoneVerified,
        boolean googleLinked,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        TenantSummary tenant) {

    public static PlatformUserDto summary(PlatformUser user) {
        return new PlatformUserDto(user.getId(), user.getTenantId(), user.getEmail(),
                user.getFullName(), user.getRole(), user.getPhone(),
                user.isActive(), user.isPhoneVerified(),
                user.getGoogleSub() != null,
                user.getLastLoginAt(), user.getCreatedAt(), null);
    }

    public static PlatformUserDto detail(PlatformAdminService.UserWithTenant view) {
        PlatformUser u = view.user();
        return new PlatformUserDto(u.getId(), u.getTenantId(), u.getEmail(),
                u.getFullName(), u.getRole(), u.getPhone(),
                u.isActive(), u.isPhoneVerified(),
                u.getGoogleSub() != null,
                u.getLastLoginAt(), u.getCreatedAt(),
                new TenantSummary(view.tenant().getId(), view.tenant().getName(),
                        view.tenant().getSlug(), view.tenant().getVerticalId(),
                        view.tenant().getPlanId(), view.tenant().getStatus()));
    }

    public record TenantSummary(UUID id, String name, String slug, String verticalId,
                                String planId, String status) {
    }
}
