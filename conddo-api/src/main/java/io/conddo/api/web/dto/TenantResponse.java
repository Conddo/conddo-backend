package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String slug,
        String verticalId,
        String planId,
        String status,
        OffsetDateTime createdAt
) {
    public static TenantResponse from(Tenant t) {
        return new TenantResponse(
                t.getId(), t.getName(), t.getSlug(),
                t.getVerticalId(), t.getPlanId(), t.getStatus(), t.getCreatedAt());
    }
}
