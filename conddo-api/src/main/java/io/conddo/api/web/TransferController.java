package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.IncomingTransfer;
import io.conddo.core.service.TransferService;
import io.conddo.core.service.TransferService.MatchResult;
import io.conddo.core.service.TransferService.Receipt;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * The money feed (Moniepoint/OPay model). {@code GET /transfers/incoming}
 * lists transfers / terminal sales from the tenant's connected accounts,
 * each with sender, amount, time and a matched/unmatched flag.
 * {@code POST /transfers/{id}/match} confirms a match to an invoice or
 * order — the server marks the invoice paid and issues the receipt.
 * Idempotent: re-matching an already-matched transfer returns the
 * existing match.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final String READ = "@staffAccess.canRead('payments')";
    private static final String WRITE = "@staffAccess.canWrite('payments')";

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /** The money feed, newest first. {@code status} ∈ unmatched | matched | all. */
    @GetMapping("/incoming")
    @PreAuthorize(READ)
    public ApiResponse<List<TransferRow>> incoming(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(status).stream().map(TransferRow::from).toList());
    }

    /**
     * Confirm a match to an invoice or order. Body carries exactly one of
     * {@code invoiceId} / {@code orderId}. The server marks the invoice
     * paid and issues the receipt; {@code alreadyMatched} is true when the
     * transfer was matched before (idempotent replay).
     */
    @PostMapping("/{id}/match")
    @PreAuthorize(WRITE)
    public ApiResponse<MatchResponse> match(@PathVariable UUID id,
                                            @Valid @RequestBody MatchRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        MatchResult result = service.match(id, body.invoiceId(), body.orderId(),
                body.note(), userId);
        return ApiResponse.ok(new MatchResponse(
                TransferRow.from(result.transfer()),
                result.alreadyMatched(),
                result.receipt() == null ? null : ReceiptDto.from(result.receipt())));
    }

    // ----- wire records ------------------------------------------------------

    public record MatchRequest(UUID invoiceId, UUID orderId, String note) {
    }

    public record MatchResponse(TransferRow transfer, boolean alreadyMatched, ReceiptDto receipt) {
    }

    public record TransferRow(
            UUID id, String provider, String providerReference,
            String senderName, String senderAccountNumber, String senderBank,
            long amountKobo, String currency, OffsetDateTime receivedAt,
            String status, UUID matchedInvoiceId, UUID matchedOrderId,
            OffsetDateTime matchedAt, UUID matchedBy, String note) {
        static TransferRow from(IncomingTransfer t) {
            return new TransferRow(
                    t.getId(), t.getProvider(), t.getProviderReference(),
                    t.getSenderName(), t.getSenderAccountNumber(), t.getSenderBank(),
                    t.getAmountKobo(), t.getCurrency(), t.getReceivedAt(),
                    t.getStatus(), t.getMatchedInvoiceId(), t.getMatchedOrderId(),
                    t.getMatchedAt(), t.getMatchedBy(), t.getNote());
        }
    }

    public record ReceiptDto(String type, UUID id, String reference, String status) {
        static ReceiptDto from(Receipt r) {
            return new ReceiptDto(r.type(), r.id(), r.reference(), r.status());
        }
    }
}
