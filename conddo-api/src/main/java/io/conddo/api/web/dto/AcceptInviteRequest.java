package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for POST /auth/accept-invite (HANDOFF_2026-06-12 §5).
 * {@code fullName} is optional — overrides the BE pre-fill from the
 * invite if provided.
 */
public record AcceptInviteRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8) String password,
        String fullName
) {
}
