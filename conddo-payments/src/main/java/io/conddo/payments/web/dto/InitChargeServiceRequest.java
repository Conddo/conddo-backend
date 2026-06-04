package io.conddo.payments.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Body for the service-to-service {@code POST /api/payments/internal/charges}
 * — conddo-api uses this when a tenant's booking flow needs a payment without
 * having a tenant Bearer JWT to forward (the FE → conddo-api hop is
 * tenant-authed; the conddo-api → payments hop uses the service token).
 */
public record InitChargeServiceRequest(@NotNull UUID tenantId,
                                       @NotBlank String tenantSlug,
                                       UUID orderId,
                                       UUID bookingId,
                                       UUID customerId,
                                       @NotBlank @Email String customerEmail,
                                       @NotBlank String customerName,
                                       String description,
                                       String returnUrl,
                                       @Positive long amountKobo) {
}
