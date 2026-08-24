package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.Receipt;
import io.conddo.core.repository.InvoiceRepository;
import io.conddo.core.repository.ReceiptRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Receipt generation, listing, refund, and send.
 *
 * <p>Every receipt is bound to a paid invoice. Mobile flow: user pays
 * an invoice → generates a receipt → shares it via WhatsApp/email.
 *
 * <p>Numbering is per-tenant sequential + year-scoped
 * ({@code RCP-2026-0001}), lifted from the {@link InvoiceService}
 * pattern so both books use the same UPSERT-RETURNING trick.
 *
 * <p>The {@code POST /receipts} endpoint accepts an optional
 * client-supplied {@code id} so the mobile app's offline queue can
 * replay generation without duplicating — same idempotency contract
 * the mobile spec uses for orders.
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    private final ReceiptRepository receiptRepo;
    private final InvoiceRepository invoiceRepo;
    private final TenantSession tenantSession;

    @PersistenceContext
    private EntityManager em;

    public ReceiptService(ReceiptRepository receiptRepo, InvoiceRepository invoiceRepo,
                          TenantSession tenantSession) {
        this.receiptRepo = receiptRepo;
        this.invoiceRepo = invoiceRepo;
        this.tenantSession = tenantSession;
    }

    // ----- reads --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Receipt> list(String status) {
        tenantSession.bind();
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return receiptRepo.findAllByOrderByPaidAtDesc();
        }
        return receiptRepo.findByStatusOrderByPaidAtDesc(status);
    }

    @Transactional(readOnly = true)
    public Receipt get(UUID id) {
        tenantSession.bind();
        return receiptRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Receipt not found: " + id));
    }

    // ----- generate -----------------------------------------------------

    /**
     * Create a receipt from a paid invoice. Idempotent by client-supplied
     * id when present (mobile replays after offline). Refuses to create
     * receipts for invoices that aren't yet paid — a receipt for an
     * unpaid invoice is a lie.
     */
    @Transactional
    public Receipt generateFromInvoice(GenerateInput input) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();

        // Idempotency short-circuit — the client's UUID owns the row.
        if (input.id() != null) {
            Optional<Receipt> existing = receiptRepo.findById(input.id());
            if (existing.isPresent()) return existing.get();
        }

        Invoice invoice = invoiceRepo.findById(input.invoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + input.invoiceId()));
        if (!Invoice.STATUS_PAID.equals(invoice.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot generate a receipt for an unpaid invoice (status: " + invoice.getStatus() + ")");
        }

        int year = LocalDate.now().getYear();
        long seq = nextReceiptNumber(tenantId, year);
        String receiptNumber = String.format("RCP-%d-%04d", year, seq);

        Receipt r = new Receipt();
        if (input.id() != null) r.setId(input.id());
        r.setTenantId(tenantId);
        r.setInvoiceId(invoice.getId());
        r.setOrderId(input.orderId() != null ? input.orderId() : invoice.getLinkedOrderId());
        r.setReceiptNumber(receiptNumber);

        // Snapshot from the invoice — never from the CRM live (see V79
        // rationale).
        r.setCustomerName(invoice.getCustomerName());
        r.setCustomerEmail(invoice.getCustomerEmail());
        r.setCustomerPhone(invoice.getCustomerPhone());
        r.setCurrency(invoice.getCurrency());
        r.setAmountKobo(input.amountKobo() != null ? input.amountKobo() : invoice.getTotalKobo());

        r.setPaymentMethod(input.paymentMethod() != null ? input.paymentMethod()
                : (invoice.getPaidMethod() != null ? invoice.getPaidMethod() : "other"));
        r.setPaymentReference(input.paymentReference() != null ? input.paymentReference()
                : invoice.getPaymentReference());
        r.setPaidAt(input.paidAt() != null ? input.paidAt()
                : (invoice.getPaidAt() != null ? invoice.getPaidAt() : OffsetDateTime.now()));
        r.setNotes(input.notes());

        return receiptRepo.save(r);
    }

    /**
     * Refund a receipt — full or partial. Idempotent on the amount, but
     * a second refund at a different amount just overwrites (there's no
     * partial-refund history built here).
     */
    @Transactional
    public Receipt refund(UUID id, long amountKobo, String reason) {
        tenantSession.bind();
        Receipt r = get(id);
        r.refund(amountKobo, reason);
        return receiptRepo.save(r);
    }

    /**
     * Track a send event. Actual WhatsApp / email delivery lives on the
     * notification layer — this just records what channel + when so the
     * mobile shows "Sent via WhatsApp 2 min ago".
     */
    @Transactional
    public Receipt markSent(UUID id, String channel) {
        tenantSession.bind();
        Receipt r = get(id);
        r.markSent(channel);
        return receiptRepo.save(r);
    }

    // ----- internal ------------------------------------------------------

    /** Per-tenant year-scoped counter. Same UPSERT-RETURNING trick as
     *  {@link InvoiceService#nextInvoiceNumber}. */
    private long nextReceiptNumber(UUID tenantId, int year) {
        Number result = (Number) em.createNativeQuery(
                "INSERT INTO receipt_sequences (tenant_id, year, last_number) "
                        + "VALUES (:tenantId, :year, 1) "
                        + "ON CONFLICT (tenant_id, year) DO UPDATE "
                        + "  SET last_number = receipt_sequences.last_number + 1 "
                        + "RETURNING last_number")
                .setParameter("tenantId", tenantId)
                .setParameter("year", year)
                .getSingleResult();
        return result.longValue();
    }

    /** Inputs for {@link #generateFromInvoice}. Only invoiceId is required —
     *  every other field either defaults from the invoice or is optional. */
    public record GenerateInput(
            UUID id,
            UUID invoiceId,
            UUID orderId,
            Long amountKobo,
            String paymentMethod,
            String paymentReference,
            OffsetDateTime paidAt,
            String notes) {}
}
