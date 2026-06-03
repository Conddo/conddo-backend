package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Sign in with a Google ID token (ACTION_LIST §1a). Posted to {@code /auth/google}. */
public record GoogleLoginRequest(@NotBlank String idToken, @NotBlank String tenantSlug) {
}
