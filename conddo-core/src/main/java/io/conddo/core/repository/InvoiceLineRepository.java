package io.conddo.core.repository;

import io.conddo.core.domain.InvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {

    List<InvoiceLine> findByInvoiceIdOrderBySortOrderAsc(UUID invoiceId);

    @Modifying
    @Query("DELETE FROM InvoiceLine l WHERE l.invoiceId = :invoiceId")
    int deleteByInvoiceId(@Param("invoiceId") UUID invoiceId);

    /** Public-view lookup — same GUC requirement as
     *  {@code InvoiceRepository.findByPublicTokenForPublic}. */
    @Query(value = "SELECT * FROM invoice_lines WHERE invoice_id = :invoiceId "
            + "ORDER BY sort_order ASC", nativeQuery = true)
    List<InvoiceLine> findByInvoiceIdForPublic(@Param("invoiceId") UUID invoiceId);
}
