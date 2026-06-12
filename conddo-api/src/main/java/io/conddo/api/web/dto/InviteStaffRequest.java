package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Invite a staff member (HANDOFF_2026-06-12 §5). {@code staffRole}
 * is one of MANAGER / PHARMACIST / CASHIER / STOCK_MANAGER /
 * BOOKKEEPER. The platform role is always STAFF — owners are
 * created through signup, not invite.
 */
public record InviteStaffRequest(
        @NotBlank @Email String email,
        @NotBlank String staffRole,
        String fullName
) {
}
