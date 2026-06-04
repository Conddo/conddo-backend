package io.conddo.api.web.dto;

import io.conddo.core.domain.Booking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A calendar event / booking (§11.5). The list view uses the core fields
 * {@code {id, customer, service, start, end, mode, status}}; detail also reads
 * {@code amount} and {@code notes}.
 */
public record BookingEvent(
        UUID id,
        UUID customerId,
        String customer,
        String service,
        OffsetDateTime start,
        OffsetDateTime end,
        String mode,
        String status,
        BigDecimal amount,
        String notes,
        // MS-2 — per-room studio bookings + deposit tracking. Null on legacy
        // bookings (Type-A studios and beyond), so the FE renders the legacy
        // shape unchanged when these fields are absent.
        UUID resourceId,
        String sessionType,
        Long depositAmountKobo,
        String depositStatus
) {
    public static BookingEvent from(Booking b) {
        return new BookingEvent(b.getId(), b.getCustomerId(), b.getCustomerName(), b.getService(),
                b.getStartsAt(), b.getEndsAt(), b.getMode(), b.getStatus(), b.getAmount(), b.getNotes(),
                b.getResourceId(), b.getSessionType(), b.getDepositAmountKobo(), b.getDepositStatus());
    }
}
