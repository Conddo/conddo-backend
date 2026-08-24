package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Receipt;
import io.conddo.core.service.ReceiptService;
import io.conddo.core.service.ReceiptService.GenerateInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant receipts — mobile-app "Payments/Receipts" surface.
 *
 * <ul>
 *   <li>{@code GET /receipts}                — list, filter by status</li>
 *   <li>{@code GET /receipts/{id}}           — detail</li>
 *   <li>{@code POST /receipts}               — generate from a paid invoice
 *                                              (mobile sync-queue-replayable
 *                                              via optional client id)</li>
 *   <li>{@code POST /receipts/{id}/refund}   — record refund (full or partial)</li>
 *   <li>{@code POST /receipts/{id}/send}     — track WhatsApp / email delivery</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private final ReceiptService service;

    public ReceiptController(ReceiptService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ReceiptView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(status).stream().map(ReceiptView::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<ReceiptView> get(@PathVariable UUID id) {
        return ApiResponse.ok(ReceiptView.of(service.get(id)));
    }

    @PostMapping
    public ApiResponse<ReceiptView> create(@Valid @RequestBody CreateRequest req) {
        return ApiResponse.ok(ReceiptView.of(service.generateFromInvoice(new GenerateInput(
                req.id(),
                req.invoiceId(),
                req.orderId(),
                req.amountKobo(),
                req.paymentMethod(),
                req.paymentReference(),
                req.paidAt(),
                req.notes()))));
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<ReceiptView> refund(@PathVariable UUID id,
                                           @Valid @RequestBody RefundRequest req) {
        return ApiResponse.ok(ReceiptView.of(service.refund(id, req.amountKobo(), req.reason())));
    }

    @PostMapping("/{id}/send")
    public ApiResponse<ReceiptView> send(@PathVariable UUID id,
                                         @Valid @RequestBody SendRequest req) {
        // Delivery itself (WhatsApp / email) belongs on the notification
        // layer — this endpoint records the send event so the mobile can
        // show "Sent via WhatsApp X minutes ago". Wire real delivery
        // when the notification service ships the channel adapters.
        return ApiResponse.ok(ReceiptView.of(service.markSent(id, req.channel())));
    }

    // ----- wire shape ------------------------------------------------------

    /** Only invoiceId is required — everything else defaults from the invoice. */
    public record CreateRequest(
            UUID id,
            @NotNull UUID invoiceId,
            UUID orderId,
            Long amountKobo,
            String paymentMethod,
            String paymentReference,
            OffsetDateTime paidAt,
            String notes) {}

    public record RefundRequest(
            @NotNull @Min(1) Long amountKobo,
            String reason) {}

    public record SendRequest(
            @jakarta.validation.constraints.NotBlank String channel) {}

    public record ReceiptView(
            UUID id, UUID invoiceId, UUID orderId,
            String receiptNumber,
            String customerName, String customerEmail, String customerPhone,
            String currency, long amountKobo,
            String paymentMethod, String paymentReference,
            OffsetDateTime paidAt, OffsetDateTime issuedAt, String notes,
            String status, long refundAmountKobo, OffsetDateTime refundedAt, String refundReason,
            OffsetDateTime lastSentAt, String lastSentChannel) {
        static ReceiptView of(Receipt r) {
            return new ReceiptView(
                    r.getId(), r.getInvoiceId(), r.getOrderId(),
                    r.getReceiptNumber(),
                    r.getCustomerName(), r.getCustomerEmail(), r.getCustomerPhone(),
                    r.getCurrency(), r.getAmountKobo(),
                    r.getPaymentMethod(), r.getPaymentReference(),
                    r.getPaidAt(), r.getIssuedAt(), r.getNotes(),
                    r.getStatus(), r.getRefundAmountKobo(), r.getRefundedAt(), r.getRefundReason(),
                    r.getLastSentAt(), r.getLastSentChannel());
        }
    }
}
