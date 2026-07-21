package io.conddo.core.repository;

import io.conddo.core.domain.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    Optional<PaymentIntent> findByProviderReference(String providerReference);

    Optional<PaymentIntent> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** Reconciliation cron — pick up intents stuck in pending. */
    List<PaymentIntent> findByStatusAndInitiatedAtBefore(String status, OffsetDateTime cutoff);

    List<PaymentIntent> findByOriginOrderId(UUID orderId);
    List<PaymentIntent> findByOriginInvoiceId(UUID invoiceId);
    List<PaymentIntent> findByOriginBookingId(UUID bookingId);
    List<PaymentIntent> findByOriginLinkId(UUID linkId);
}
