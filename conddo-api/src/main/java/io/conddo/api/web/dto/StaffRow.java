package io.conddo.api.web.dto;

import io.conddo.core.domain.User;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A staff member (§11.10 / HANDOFF_2026-06-12). {@code status} is
 * derived from the user's status + active flag: inactive → "inactive";
 * INVITED → "invited"; otherwise → "active".
 */
public record StaffRow(
        UUID id,
        String name,
        String email,
        String role,
        String staffRole,
        String status,
        OffsetDateTime lastActive
) {
    public static StaffRow from(User u) {
        String status = !u.isActive() ? "inactive"
                : User.STATUS_INVITED.equals(u.getStatus()) ? "invited"
                : "active";
        return new StaffRow(u.getId(), u.getFullName(), u.getEmail(), u.getRole(),
                u.getStaffRole(), status, u.getLastLoginAt());
    }
}
