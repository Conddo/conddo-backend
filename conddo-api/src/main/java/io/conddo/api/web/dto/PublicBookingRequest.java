package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** A client's self-book request (§11.5 PUBLIC). Lands as a pending booking.
 *  {@code customerEmail} is optional but strongly encouraged — the customer
 *  confirmation email cannot fire without it. Enforced softly at the FE. */
public record PublicBookingRequest(
        @NotBlank String customerName,
        String customerPhone,
        @Email String customerEmail,
        String service,
        @NotNull OffsetDateTime start
) {
}
