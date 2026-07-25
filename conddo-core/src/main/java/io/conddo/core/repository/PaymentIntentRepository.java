package io.conddo.core.repository;

import io.conddo.core.domain.PaymentIntent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    /** Tenant-scoped listing, most-recent-first. Uses the current tenant
     *  binding — repository sees the RLS-filtered rows. */
    Page<PaymentIntent> findByTenantIdOrderByInitiatedAtDesc(UUID tenantId, Pageable pageable);

    /** Tenant-scoped listing filtered by status. */
    Page<PaymentIntent> findByTenantIdAndStatusOrderByInitiatedAtDesc(UUID tenantId, String status, Pageable pageable);

    /** Sum succeeded intents for the tenant balance. Returns 0 when no rows. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(p.amountKobo), 0) FROM PaymentIntent p " +
            "WHERE p.tenantId = :tenantId AND p.status = :status")
    long sumAmountKoboByStatus(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                @org.springframework.data.repository.query.Param("status") String status);

    /** Count intents by status for the tenant summary. */
    long countByTenantIdAndStatus(UUID tenantId, String status);

    Optional<PaymentIntent> findByProviderReference(String providerReference);

    Optional<PaymentIntent> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** Reconciliation cron — pick up intents stuck in pending. */
    List<PaymentIntent> findByStatusAndInitiatedAtBefore(String status, OffsetDateTime cutoff);

    List<PaymentIntent> findByOriginOrderId(UUID orderId);
    List<PaymentIntent> findByOriginInvoiceId(UUID invoiceId);
    List<PaymentIntent> findByOriginBookingId(UUID bookingId);
    List<PaymentIntent> findByOriginLinkId(UUID linkId);
}
