package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Start signup with a Google ID token (ACTION_LIST §1a). Posted to
 * {@code /auth/register/start-google}. Phone is still SMS-verified — Google
 * can vouch for the email, not for the Nigerian phone number.
 */
public record GoogleRegisterStartRequest(
        @NotBlank String idToken,
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{7,15}$",
                message = "phone must be 7-15 digits, optionally prefixed with +") String phone) {
}
