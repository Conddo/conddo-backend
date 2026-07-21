package io.conddo.core.repository;

import io.conddo.core.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findAllByOrderByCreatedAtDesc();

    List<Invoice> findByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);

    /** Public share-link resolver — caller MUST have bound the
     *  {@code app.public_resolver=true} GUC so RLS lets the row through.
     *  The token itself is unguessable, so open lookup is safe. */
    @Query(value = "SELECT * FROM invoices WHERE public_token = :token LIMIT 1",
            nativeQuery = true)
    Optional<Invoice> findByPublicTokenForPublic(@Param("token") String token);

    /** Cross-tenant next-number pick for admin surfaces (unused in pass 1
     *  but wired now so we don't need a second migration for the admin
     *  invoices tab in a later pass). */
    @Query(value = "SELECT * FROM invoices WHERE tenant_id = :tenantId "
            + "ORDER BY created_at DESC", nativeQuery = true)
    List<Invoice> findByTenantIdCrossTenant(@Param("tenantId") UUID tenantId);
}
