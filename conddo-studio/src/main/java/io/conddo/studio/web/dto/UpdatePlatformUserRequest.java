package io.conddo.studio.web.dto;

/**
 * Admin patch payload for {@code PATCH /api/jobs/admin/platform/users/{id}}
 * (§23.3). Every field optional. {@code role} ∈ {@code TENANT_ADMIN / STAFF /
 * CUSTOMER} (validated server-side). {@code tenantId} is intentionally NOT
 * accepted — changing a user's tenant would orphan their content.
 */
public record UpdatePlatformUserRequest(String role, Boolean active, String fullName) {
}
