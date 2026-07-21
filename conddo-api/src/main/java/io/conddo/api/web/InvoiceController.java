package io.conddo.api.web;

import io.conddo.api.billing.RequiresFeature;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.InvoiceLine;
import io.conddo.core.service.InvoiceService;
import io.conddo.core.service.InvoiceService.NewInvoice;
import io.conddo.core.service.InvoiceService.NewLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-side invoice CRUD. Growth-gated via {@code @RequiresFeature}.
 * Read is open to any staff role with orders read; write is owner-only
 * because invoice numbering + payment status touch accounting.
 */
@RestController
@RequestMapping("/api/v1/invoices")
@RequiresFeature(value = "invoicing", requiredPlan = "growth")
public class InvoiceController {

    private static final String READ = "@staffAccess.canRead('orders')";
    private static final String WRITE = "@staffAccess.ownerOnly()";

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<InvoiceRow>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(status).stream().map(InvoiceRow::from).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<InvoiceDetail> get(@PathVariable UUID id) {
        Invoice invoice = service.get(id);
        List<InvoiceLine> lines = service.lines(id);
        return ApiResponse.ok(InvoiceDetail.of(invoice, lines));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<InvoiceDetail>> create(@Valid @RequestBody UpsertRequest body) {
        Invoice invoice = service.create(toInput(body));
        List<InvoiceLine> lines = service.lines(invoice.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(InvoiceDetail.of(invoice, lines)));
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<InvoiceDetail> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpsertRequest body) {
        Invoice invoice = service.updateDraft(id, toInput(body));
        List<InvoiceLine> lines = service.lines(id);
        return ApiResponse.ok(InvoiceDetail.of(invoice, lines));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize(WRITE)
    public ApiResponse<InvoiceRow> markSent(@PathVariable UUID id) {
        return ApiResponse.ok(InvoiceRow.from(service.markSent(id)));
    }

    /**
     * Send the invoice to the customer by email. Also flips draft → sent.
     * Idempotent when already sent — safe to hit twice as a re-send.
     */
    @PostMapping("/{id}/email")
    @PreAuthorize(WRITE)
    public ApiResponse<InvoiceRow> emailInvoice(@PathVariable UUID id) {
        return ApiResponse.ok(InvoiceRow.from(service.sendInvoiceToCustomer(id)));
    }

    @PostMapping("/{id}/mark-paid")
    @PreAuthorize(WRITE)
    public ApiResponse<InvoiceRow> markPaid(@PathVariable UUID id,
                                             @RequestBody MarkPaidRequest body) {
        String method = body == null || body.method() == null ? "cash" : body.method();
        return ApiResponse.ok(InvoiceRow.from(service.markPaidManually(id, method)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<InvoiceRow> voidInvoice(@PathVariable UUID id) {
        return ApiResponse.ok(InvoiceRow.from(service.voidInvoice(id)));
    }

    // ----- wire records ----------------------------------------------------

    public record UpsertRequest(
            @NotBlank String customerName,
            UUID customerId,
            String customerEmail,
            String customerPhone,
            String customerAddress,
            LocalDate issueDate,
            LocalDate dueDate,
            String notes,
            String terms,
            @PositiveOrZero Long discountKobo,
            UUID linkedOrderId,
            UUID linkedBookingId,
            List<LineRequest> lines) {}

    public record LineRequest(
            @NotBlank String description,
            BigDecimal quantity,
            @PositiveOrZero long unitPriceKobo,
            BigDecimal taxRatePercent) {}

    public record MarkPaidRequest(String method) {}

    public record InvoiceRow(UUID id, String invoiceNumber, String customerName,
                              long totalKobo, String status, LocalDate issueDate,
                              LocalDate dueDate, OffsetDateTime createdAt,
                              String publicToken) {
        static InvoiceRow from(Invoice i) {
            return new InvoiceRow(i.getId(), i.getInvoiceNumber(), i.getCustomerName(),
                    i.getTotalKobo(), i.getStatus(), i.getIssueDate(),
                    i.getDueDate(), i.getCreatedAt(), i.getPublicToken());
        }
    }

    public record InvoiceDetail(
            UUID id, String invoiceNumber,
            String customerName, String customerEmail, String customerPhone, String customerAddress,
            UUID customerId,
            String currency, long subtotalKobo, long taxKobo, long discountKobo, long totalKobo,
            String status, LocalDate issueDate, LocalDate dueDate,
            OffsetDateTime paidAt, String paidMethod, String paymentReference,
            String notes, String terms,
            String publicToken,
            UUID linkedOrderId, UUID linkedBookingId,
            List<LineRow> lines) {
        static InvoiceDetail of(Invoice i, List<InvoiceLine> lines) {
            return new InvoiceDetail(
                    i.getId(), i.getInvoiceNumber(),
                    i.getCustomerName(), i.getCustomerEmail(), i.getCustomerPhone(), i.getCustomerAddress(),
                    i.getCustomerId(),
                    i.getCurrency(), i.getSubtotalKobo(), i.getTaxKobo(), i.getDiscountKobo(), i.getTotalKobo(),
                    i.getStatus(), i.getIssueDate(), i.getDueDate(),
                    i.getPaidAt(), i.getPaidMethod(), i.getPaymentReference(),
                    i.getNotes(), i.getTerms(),
                    i.getPublicToken(),
                    i.getLinkedOrderId(), i.getLinkedBookingId(),
                    lines.stream().map(LineRow::from).toList());
        }
    }

    public record LineRow(UUID id, String description, BigDecimal quantity,
                           long unitPriceKobo, BigDecimal taxRatePercent,
                           long lineTotalKobo, int sortOrder) {
        static LineRow from(InvoiceLine l) {
            return new LineRow(l.getId(), l.getDescription(), l.getQuantity(),
                    l.getUnitPriceKobo(), l.getTaxRatePercent(),
                    l.getLineTotalKobo(), l.getSortOrder());
        }
    }

    // ----- helpers ---------------------------------------------------------

    private static NewInvoice toInput(UpsertRequest body) {
        NewInvoice out = new NewInvoice();
        out.customerName = body.customerName();
        out.customerId = body.customerId();
        out.customerEmail = body.customerEmail();
        out.customerPhone = body.customerPhone();
        out.customerAddress = body.customerAddress();
        out.issueDate = body.issueDate();
        out.dueDate = body.dueDate();
        out.notes = body.notes();
        out.terms = body.terms();
        out.discountKobo = body.discountKobo();
        out.linkedOrderId = body.linkedOrderId();
        out.linkedBookingId = body.linkedBookingId();
        out.lines = body.lines() == null ? List.of() : body.lines().stream().map(l -> {
            NewLine n = new NewLine();
            n.description = l.description();
            n.quantity = l.quantity();
            n.unitPriceKobo = l.unitPriceKobo();
            n.taxRatePercent = l.taxRatePercent();
            return n;
        }).toList();
        return out;
    }
}
