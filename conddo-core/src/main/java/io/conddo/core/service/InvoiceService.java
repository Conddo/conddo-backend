package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.InvoiceLine;
import io.conddo.core.domain.Tenant;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.InvoiceLineRepository;
import io.conddo.core.repository.InvoiceRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-side invoice CRUD. Also provides the public-view resolver
 * that {@code PublicInvoiceController} calls with an unguessable token.
 *
 * <p>Numbering: per-tenant + year-scoped. On create, we UPSERT into
 * {@code invoice_sequences (tenant_id, year)} with a returning-clause
 * that hands back the incremented number atomically — safe under
 * concurrent creates because Postgres serialises the row-level lock.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter HUMAN_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy");

    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineRepository lineRepo;
    private final TenantRepository tenantRepo;
    private final NotificationService notificationService;
    private final TenantSession tenantSession;
    private final String appBaseUrl;

    @PersistenceContext
    private EntityManager em;

    public InvoiceService(InvoiceRepository invoiceRepo,
                          InvoiceLineRepository lineRepo,
                          TenantRepository tenantRepo,
                          NotificationService notificationService,
                          TenantSession tenantSession,
                          @Value("${conddo.app.base-url:https://app.getconddo.com}") String appBaseUrl) {
        this.invoiceRepo = invoiceRepo;
        this.lineRepo = lineRepo;
        this.tenantRepo = tenantRepo;
        this.notificationService = notificationService;
        this.tenantSession = tenantSession;
        this.appBaseUrl = trimTrailingSlash(appBaseUrl);
    }

    private static String trimTrailingSlash(String v) {
        if (v == null) return "";
        String s = v.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ----- list + get ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Invoice> list(String statusFilter) {
        tenantSession.bind();
        if (statusFilter == null || statusFilter.isBlank()) {
            return invoiceRepo.findAllByOrderByCreatedAtDesc();
        }
        return invoiceRepo.findByStatusOrderByCreatedAtDesc(statusFilter);
    }

    @Transactional(readOnly = true)
    public Invoice get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    @Transactional(readOnly = true)
    public List<InvoiceLine> lines(UUID invoiceId) {
        tenantSession.bind();
        // Bounce through require so a wrong-tenant id 404s instead of
        // silently returning an empty list.
        require(invoiceId);
        return lineRepo.findByInvoiceIdOrderBySortOrderAsc(invoiceId);
    }

    // ----- create + update -------------------------------------------------

    @Transactional
    public Invoice create(NewInvoice input) {
        tenantSession.bind();
        Objects.requireNonNull(input.customerName, "customerName is required");
        if (input.lines == null || input.lines.isEmpty()) {
            throw new IllegalArgumentException("At least one invoice line is required");
        }

        UUID tenantId = TenantContext.require();
        int year = LocalDate.now().getYear();
        long seq = nextInvoiceNumber(tenantId, year);
        String invoiceNumber = String.format("INV-%d-%04d", year, seq);
        String publicToken = newPublicToken();

        Invoice invoice = new Invoice(tenantId, invoiceNumber, input.customerName, publicToken);
        invoice.setCustomerId(input.customerId);
        invoice.setCustomerEmail(input.customerEmail);
        invoice.setCustomerPhone(input.customerPhone);
        invoice.setCustomerAddress(input.customerAddress);
        invoice.setDueDate(input.dueDate);
        invoice.setNotes(input.notes);
        invoice.setTerms(input.terms);
        invoice.setLinkedOrderId(input.linkedOrderId);
        invoice.setLinkedBookingId(input.linkedBookingId);
        if (input.issueDate != null) {
            invoice.setIssueDate(input.issueDate);
        }

        invoice = invoiceRepo.save(invoice);

        long subtotal = 0L;
        long tax = 0L;
        int order = 0;
        for (NewLine ln : input.lines) {
            InvoiceLine line = new InvoiceLine(
                    invoice.getId(), tenantId,
                    ln.description,
                    ln.quantity == null ? BigDecimal.ONE : ln.quantity,
                    ln.unitPriceKobo,
                    ln.taxRatePercent,
                    order++);
            lineRepo.save(line);
            subtotal += line.getLineTotalKobo();
            tax += line.taxKobo();
        }

        long discount = input.discountKobo == null ? 0L : input.discountKobo;
        invoice.applyTotals(subtotal, tax, discount);
        return invoiceRepo.save(invoice);
    }

    @Transactional
    public Invoice updateDraft(UUID id, NewInvoice input) {
        tenantSession.bind();
        Invoice invoice = require(id);
        if (!Invoice.STATUS_DRAFT.equals(invoice.getStatus())) {
            throw new IllegalStateException("Only draft invoices can be edited.");
        }

        invoice.setCustomerName(input.customerName);
        invoice.setCustomerId(input.customerId);
        invoice.setCustomerEmail(input.customerEmail);
        invoice.setCustomerPhone(input.customerPhone);
        invoice.setCustomerAddress(input.customerAddress);
        invoice.setDueDate(input.dueDate);
        invoice.setNotes(input.notes);
        invoice.setTerms(input.terms);
        if (input.issueDate != null) {
            invoice.setIssueDate(input.issueDate);
        }

        // Reset lines. Draft lines are cheap and the whole point of draft
        // is that the tenant hasn't shared the invoice yet — nothing to
        // preserve.
        lineRepo.deleteByInvoiceId(id);
        em.flush();
        long subtotal = 0L;
        long tax = 0L;
        int order = 0;
        for (NewLine ln : input.lines) {
            InvoiceLine line = new InvoiceLine(
                    id, invoice.getTenantId(),
                    ln.description,
                    ln.quantity == null ? BigDecimal.ONE : ln.quantity,
                    ln.unitPriceKobo,
                    ln.taxRatePercent,
                    order++);
            lineRepo.save(line);
            subtotal += line.getLineTotalKobo();
            tax += line.taxKobo();
        }
        long discount = input.discountKobo == null ? 0L : input.discountKobo;
        invoice.applyTotals(subtotal, tax, discount);
        return invoiceRepo.save(invoice);
    }

    /** Flip from draft to sent. Idempotent when already sent. */
    @Transactional
    public Invoice markSent(UUID id) {
        tenantSession.bind();
        Invoice invoice = require(id);
        invoice.markSent();
        return invoiceRepo.save(invoice);
    }

    /**
     * Manual mark-paid — cash / transfer / other. Auto-fires a receipt
     * email to the customer when we have an email on file. Delivery
     * failures are logged but never bubbled — the payment is real,
     * the email is best-effort.
     */
    @Transactional
    public Invoice markPaidManually(UUID id, String method) {
        tenantSession.bind();
        Invoice invoice = require(id);
        invoice.markPaidManually(method, OffsetDateTime.now());
        invoice = invoiceRepo.save(invoice);
        maybeSendReceiptEmail(invoice);
        return invoice;
    }

    /**
     * Send the invoice link to the customer's email. Also flips draft →
     * sent so the tenant's dashboard reflects that the customer has it.
     * Idempotent for already-sent invoices — you can re-send the email
     * without changing status.
     */
    @Transactional
    public Invoice sendInvoiceToCustomer(UUID id) {
        tenantSession.bind();
        Invoice invoice = require(id);
        if (invoice.getCustomerEmail() == null || invoice.getCustomerEmail().isBlank()) {
            throw new IllegalArgumentException(
                    "Customer email is required to send this invoice. Add it in Edit and try again.");
        }
        Tenant tenant = tenantRepo.findById(invoice.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
        try {
            notificationService.sendInvoiceEmail(
                    invoice.getCustomerEmail(),
                    invoice.getCustomerName(),
                    tenant.getName(),
                    invoice.getInvoiceNumber(),
                    formatNaira(invoice.getTotalKobo()),
                    invoice.getDueDate() == null ? "" : HUMAN_DATE.format(invoice.getDueDate()),
                    publicUrlFor(invoice),
                    invoice.getNotes(),
                    tenant.getContactPhone(),
                    tenant.getContactEmail());
        } catch (RuntimeException ex) {
            log.error("Invoice email send failed for {}: {}",
                    invoice.getInvoiceNumber(), ex.getMessage());
        }
        invoice.markSent();
        return invoiceRepo.save(invoice);
    }

    private void maybeSendReceiptEmail(Invoice invoice) {
        if (invoice.getCustomerEmail() == null || invoice.getCustomerEmail().isBlank()) {
            return;
        }
        Tenant tenant = tenantRepo.findById(invoice.getTenantId()).orElse(null);
        if (tenant == null) return;
        try {
            notificationService.sendInvoiceReceiptEmail(
                    invoice.getCustomerEmail(),
                    invoice.getCustomerName(),
                    tenant.getName(),
                    invoice.getInvoiceNumber(),
                    formatNaira(invoice.getTotalKobo()),
                    invoice.getPaidAt() == null ? "" : HUMAN_DATE.format(invoice.getPaidAt().toLocalDate()),
                    invoice.getPaidMethod() == null ? "" : invoice.getPaidMethod(),
                    publicUrlFor(invoice),
                    tenant.getContactPhone(),
                    tenant.getContactEmail());
        } catch (RuntimeException ex) {
            log.error("Receipt email send failed for {}: {}",
                    invoice.getInvoiceNumber(), ex.getMessage());
        }
    }

    private String publicUrlFor(Invoice invoice) {
        return appBaseUrl + "/i/" + invoice.getPublicToken();
    }

    /** Naira display for email bodies. Kobo → "₦1,234.56". */
    private static String formatNaira(long kobo) {
        BigDecimal naira = BigDecimal.valueOf(kobo).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return "₦" + String.format("%,.2f", naira);
    }

    @Transactional
    public Invoice voidInvoice(UUID id) {
        tenantSession.bind();
        Invoice invoice = require(id);
        invoice.voidInvoice();
        return invoiceRepo.save(invoice);
    }

    // ----- public view -----------------------------------------------------

    /**
     * Public share-link resolver. Sets the {@code app.public_resolver}
     * GUC so RLS lets the invoice row through despite no
     * {@code app.tenant_id} being bound (the caller has no JWT).
     *
     * <p>The token itself is unguessable, so returning the row on a
     * blind lookup is safe — same shape as public managed sites and
     * public bookings.
     */
    @Transactional(readOnly = true)
    public Optional<PublicView> resolveByPublicToken(String token) {
        em.createNativeQuery("SET LOCAL app.public_resolver = 'true'").executeUpdate();
        Optional<Invoice> maybe = invoiceRepo.findByPublicTokenForPublic(token);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        Invoice invoice = maybe.get();
        List<InvoiceLine> lines = lineRepo.findByInvoiceIdForPublic(invoice.getId());
        return Optional.of(new PublicView(invoice, lines));
    }

    // ----- internals -------------------------------------------------------

    private Invoice require(UUID id) {
        return invoiceRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
    }

    /**
     * UPSERT into {@code invoice_sequences} with a
     * {@code ON CONFLICT DO UPDATE ... RETURNING} that hands back the
     * new value atomically. Postgres row-level locking makes this safe
     * under concurrent creates on the same {@code (tenant_id, year)}.
     */
    private long nextInvoiceNumber(UUID tenantId, int year) {
        Number result = (Number) em.createNativeQuery(
                "INSERT INTO invoice_sequences (tenant_id, year, last_number) "
                        + "VALUES (:tenantId, :year, 1) "
                        + "ON CONFLICT (tenant_id, year) DO UPDATE "
                        + "  SET last_number = invoice_sequences.last_number + 1 "
                        + "RETURNING last_number")
                .setParameter("tenantId", tenantId)
                .setParameter("year", year)
                .getSingleResult();
        return result.longValue();
    }

    private static String newPublicToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ----- wire shapes -----------------------------------------------------

    /** Draft-input shape used by both create + updateDraft. */
    public static class NewInvoice {
        public String customerName;
        public UUID customerId;
        public String customerEmail;
        public String customerPhone;
        public String customerAddress;
        public LocalDate issueDate;
        public LocalDate dueDate;
        public String notes;
        public String terms;
        public Long discountKobo;
        public UUID linkedOrderId;
        public UUID linkedBookingId;
        public List<NewLine> lines;
    }

    public static class NewLine {
        public String description;
        public BigDecimal quantity;
        public long unitPriceKobo;
        public BigDecimal taxRatePercent;
    }

    /** Read shape for the public /i/{token} endpoint. */
    public record PublicView(Invoice invoice, List<InvoiceLine> lines) {}
}
