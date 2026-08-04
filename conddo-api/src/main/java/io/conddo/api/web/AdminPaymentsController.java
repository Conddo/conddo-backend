package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.service.AdminPaymentsService;
import io.conddo.core.service.AdminPaymentsService.PlatformSummary;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cross-tenant payment reads + support actions for the admin console.
 * SUPER_ADMIN only. Powers /admin/payments.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPaymentsController {

    private final AdminPaymentsService service;

    public AdminPaymentsController(AdminPaymentsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<PlatformSummary> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping
    public ApiResponse<PagedResponse<AdminIntentRow>> list(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<PaymentIntent> rows = service.list(status, page, size);
        return ApiResponse.ok(new PagedResponse<>(
                rows.getContent().stream().map(AdminIntentRow::of).toList(),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements(),
                rows.getTotalPages()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminIntentDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(AdminIntentDetail.of(service.get(id)));
    }

    /** Support-triage: re-verify a stuck intent against the provider. */
    @PostMapping("/{id}/reverify")
    public ApiResponse<AdminIntentDetail> reverify(@PathVariable UUID id) {
        return ApiResponse.ok(AdminIntentDetail.of(service.reverify(id)));
    }

    // ----- wire shape ------------------------------------------------------

    public record PagedResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    public record AdminIntentRow(
            UUID id,
            UUID tenantId,
            String status,
            String origin,
            long amountKobo,
            String currency,
            String provider,
            String customerName,
            String originReference,
            OffsetDateTime initiatedAt,
            OffsetDateTime completedAt) {
        static AdminIntentRow of(PaymentIntent i) {
            return new AdminIntentRow(
                    i.getId(), i.getTenantId(), i.getStatus(), i.getOrigin(),
                    i.getAmountKobo(), i.getCurrency(),
                    i.getProvider(), i.getCustomerName(),
                    i.getOriginReference(),
                    i.getInitiatedAt(), i.getCompletedAt());
        }
    }

    public record AdminIntentDetail(
            UUID id, UUID tenantId, String status, String origin,
            long amountKobo, long feeKobo, long netKobo, String currency,
            String provider, String providerReference,
            String customerName, String customerEmail, String customerPhone,
            String receivingBankName, String receivingAccountNumber, String receivingAccountName,
            String senderBankName, String senderAccountNumber,
            String matchedTransactionRef, String failureReason,
            String originReference,
            UUID originOrderId, UUID originInvoiceId, UUID originBookingId,
            OffsetDateTime initiatedAt, OffsetDateTime completedAt, OffsetDateTime lastVerifiedAt) {
        static AdminIntentDetail of(PaymentIntent i) {
            return new AdminIntentDetail(
                    i.getId(), i.getTenantId(), i.getStatus(), i.getOrigin(),
                    i.getAmountKobo(), i.getFeeKobo(), i.getNetKobo(), i.getCurrency(),
                    i.getProvider(), i.getProviderReference(),
                    i.getCustomerName(), i.getCustomerEmail(), i.getCustomerPhone(),
                    i.getReceivingBankName(), i.getReceivingAccountNumber(), i.getReceivingAccountName(),
                    i.getSenderBankName(), i.getSenderAccountNumber(),
                    i.getMatchedTransactionRef(), i.getFailureReason(),
                    i.getOriginReference(),
                    i.getOriginOrderId(), i.getOriginInvoiceId(), i.getOriginBookingId(),
                    i.getInitiatedAt(), i.getCompletedAt(), i.getLastVerifiedAt());
        }
    }
}
