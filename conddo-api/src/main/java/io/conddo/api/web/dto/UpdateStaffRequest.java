package io.conddo.api.web.dto;

/**
 * PATCH a staff member (HANDOFF_2026-06-12 §5): change sub-role
 * and/or active flag. Both optional. Owners are rejected at the
 * service layer with OWNER_PROTECTED.
 */
public record UpdateStaffRequest(String staffRole, Boolean active) {
}
