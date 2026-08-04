package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Payout;
import io.conddo.core.service.PayoutService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing payouts endpoint. Read-only — payouts are populated by
 * provider webhooks, not by tenant action.
 */
@RestController
@RequestMapping("/api/v1/payments/payouts")
public class PayoutController {

    private final PayoutService service;

    public PayoutController(PayoutService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<PayoutRow>> list() {
        return ApiResponse.ok(service.listForTenant().stream().map(PayoutRow::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<PayoutRow> get(@PathVariable UUID id) {
        try {
            return ApiResponse.ok(PayoutRow.of(service.get(id)));
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException(ex.getMessage());
        }
    }

    public record PayoutRow(
            UUID id,
            String provider,
            String providerReference,
            long amountKobo,
            String currency,
            String bankName,
            String accountNumberLast4,
            String accountName,
            String status,
            String failureReason,
            OffsetDateTime initiatedAt,
            OffsetDateTime completedAt) {
        static PayoutRow of(Payout p) {
            return new PayoutRow(
                    p.getId(), p.getProvider(), p.getProviderReference(),
                    p.getAmountKobo(), p.getCurrency(),
                    p.getBankName(), p.getAccountNumberLast4(), p.getAccountName(),
                    p.getStatus(), p.getFailureReason(),
                    p.getInitiatedAt(), p.getCompletedAt());
        }
    }
}
