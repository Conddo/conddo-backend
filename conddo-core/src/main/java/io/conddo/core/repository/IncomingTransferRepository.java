package io.conddo.core.repository;

import io.conddo.core.domain.IncomingTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The money feed. RLS-scoped for tenant reads; the webhook ingest and
 * poller dedupe on (provider, provider_reference) under
 * {@code app.cross_tenant=true}.
 */
public interface IncomingTransferRepository extends JpaRepository<IncomingTransfer, UUID> {

    List<IncomingTransfer> findAllByOrderByReceivedAtDesc();

    List<IncomingTransfer> findByStatusOrderByReceivedAtDesc(String status);

    /** Cross-tenant dedupe key — callers MUST bind app.cross_tenant first. */
    Optional<IncomingTransfer> findByProviderAndProviderReference(String provider, String providerReference);

    long countByStatus(String status);

    /** Sum of the tenant's transfers in a status — RLS-scoped. */
    @Query(value = "SELECT COALESCE(SUM(amount_kobo), 0) FROM incoming_transfers WHERE status = :status",
            nativeQuery = true)
    long sumAmountKoboByStatus(@Param("status") String status);

    /** Sum of ALL of the tenant's transfers (the "sales brought in" number). */
    @Query(value = "SELECT COALESCE(SUM(amount_kobo), 0) FROM incoming_transfers",
            nativeQuery = true)
    long sumAmountKoboByStatusAll();

    /** Cross-tenant: which provider references already exist for a tenant. */
    List<IncomingTransfer> findByTenantIdAndProvider(UUID tenantId, String provider);
}
