package io.conddo.core.repository;

import io.conddo.core.domain.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /** Tenant list — RLS scopes it to the caller's rows. Most-recent first. */
    List<Receipt> findAllByOrderByPaidAtDesc();

    /** Filter by status ('issued' | 'refunded'). */
    List<Receipt> findByStatusOrderByPaidAtDesc(String status);

    /** All receipts for a given invoice — usually 0 or 1, but the FK
     *  allows more if the tenant regenerates. */
    List<Receipt> findByInvoiceId(UUID invoiceId);

    Optional<Receipt> findByTenantIdAndReceiptNumber(UUID tenantId, String receiptNumber);
}
