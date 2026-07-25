package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.service.PaymentIntentService;
import io.conddo.core.service.PaymentIntentService.TenantBalance;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing PaymentIntent reads. Powers the tenant /payments
 * dashboard: balance card, transactions list, single-intent detail.
 */
@RestController
@RequestMapping("/api/v1/payments/intents")
public class PaymentIntentController {

    private final PaymentIntentService service;

    public PaymentIntentController(PaymentIntentService service) {
        this.service = service;
    }

    /** Paginated transaction list. Filter on status (pending|succeeded|failed|refunded|all). */
    @GetMapping
    public ApiResponse<PagedResponse<IntentRow>> list(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PaymentIntent> rows = service.listForTenant(status, page, size);
        return ApiResponse.ok(new PagedResponse<>(
                rows.getContent().stream().map(IntentRow::of).toList(),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements(),
                rows.getTotalPages()));
    }

    /** Balance + status counts for the summary card. */
    @GetMapping("/balance")
    public ApiResponse<TenantBalance> balance() {
        return ApiResponse.ok(service.tenantBalance());
    }

    @GetMapping("/{id}")
    public ApiResponse<IntentDetail> get(@PathVariable UUID id) {
        PaymentIntent intent = service.getForTenant(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        return ApiResponse.ok(IntentDetail.of(intent));
    }

    // ----- wire shape ------------------------------------------------------

    public record PagedResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    /** Compact row for the list view — cheaper wire than the full detail. */
    public record IntentRow(
            UUID id,
            String status,
            String origin,
            long amountKobo,
            String currency,
            String customerName,
            String customerEmail,
            String provider,
            String originReference,
            OffsetDateTime initiatedAt,
            OffsetDateTime completedAt) {
        static IntentRow of(PaymentIntent i) {
            return new IntentRow(
                    i.getId(), i.getStatus(), i.getOrigin(),
                    i.getAmountKobo(), i.getCurrency(),
                    i.getCustomerName(), i.getCustomerEmail(),
                    i.getProvider(), i.getOriginReference(),
                    i.getInitiatedAt(), i.getCompletedAt());
        }
    }

    /** Full detail view including receiving-account snapshot + refund state. */
    public record IntentDetail(
            UUID id,
            String status,
            String origin,
            long amountKobo,
            long feeKobo,
            long netKobo,
            String currency,
            String provider,
            String providerReference,
            String customerName,
            String customerEmail,
            String customerPhone,
            String receivingBankName,
            String receivingAccountNumber,
            String receivingAccountName,
            String senderBankName,
            String senderAccountNumber,
            String matchedTransactionRef,
            String failureReason,
            String originReference,
            UUID originOrderId,
            UUID originInvoiceId,
            UUID originBookingId,
            OffsetDateTime initiatedAt,
            OffsetDateTime completedAt,
            OffsetDateTime lastVerifiedAt) {
        static IntentDetail of(PaymentIntent i) {
            return new IntentDetail(
                    i.getId(), i.getStatus(), i.getOrigin(),
                    i.getAmountKobo(), i.getFeeKobo(), i.getNetKobo(),
                    i.getCurrency(), i.getProvider(), i.getProviderReference(),
                    i.getCustomerName(), i.getCustomerEmail(), i.getCustomerPhone(),
                    i.getReceivingBankName(), i.getReceivingAccountNumber(), i.getReceivingAccountName(),
                    i.getSenderBankName(), i.getSenderAccountNumber(),
                    i.getMatchedTransactionRef(),
                    i.getFailureReason(),
                    i.getOriginReference(),
                    i.getOriginOrderId(), i.getOriginInvoiceId(), i.getOriginBookingId(),
                    i.getInitiatedAt(), i.getCompletedAt(), i.getLastVerifiedAt());
        }
    }
}
