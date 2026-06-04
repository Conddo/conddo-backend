package io.conddo.studio.web.dto;

/**
 * Admin patch payload for {@code PATCH /api/jobs/admin/platform/tenants/{id}}
 * (§23.3). Every field optional — null = no-op. {@code status} accepts
 * {@code ACTIVE} or {@code SUSPENDED}; {@code DELETED} is reached via the
 * DELETE endpoint, not via this PATCH.
 */
public record UpdatePlatformTenantRequest(String name, String planId, String status) {
}
