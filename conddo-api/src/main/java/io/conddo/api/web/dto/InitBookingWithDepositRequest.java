package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/bookings/init-with-deposit} — the music-studio
 * killer feature: reserve a room, take a deposit before the slot is locked in.
 *
 * <p>One of {@code customerId} / {@code customerName} must be set so we can
 * stamp the booking with a person; {@code customerEmail} is always required
 * because RoutePay needs an email to send the receipt to.
 */
public record InitBookingWithDepositRequest(
        UUID customerId,
        String customerName,
        @NotBlank @Email String customerEmail,
        @NotNull UUID resourceId,
        String sessionType,
        @NotNull OffsetDateTime start,
        @NotNull OffsetDateTime end,
        String service,
        BigDecimal amount,
        @Positive long depositAmountKobo,
        String returnUrl,
        String notes) {
}
