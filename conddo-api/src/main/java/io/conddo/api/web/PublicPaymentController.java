package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.domain.Tenant;
import io.conddo.core.payments.PaymentProvider;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.PaymentIntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * PUBLIC customer-facing payment endpoints. No auth — the intent id is
 * unguessable and the rest of the intent is safe to hand to the payer
 * (they need to see the receiving account anyway).
 *
 * <p>Flow the FE walks through:
 * <ol>
 *   <li>{@code GET /public/payments/{id}} — render the pay page</li>
 *   <li>Payer transfers via their own bank</li>
 *   <li>{@code POST /public/payments/{id}/confirm} with sender info</li>
 *   <li>{@code POST /public/payments/{id}/verify} — poll until resolved</li>
 * </ol>
 * The bank pick-list at {@code GET /public/payments/banks} feeds the
 * sender-bank dropdown.
 */
@RestController
@RequestMapping("/api/v1/public/payments")
public class PublicPaymentController {

    private final PaymentIntentService payments;
    private final TenantRepository tenants;

    public PublicPaymentController(PaymentIntentService payments, TenantRepository tenants) {
        this.payments = payments;
        this.tenants = tenants;
    }

    @GetMapping("/{intentId}")
    public ApiResponse<PublicIntentDto> get(@PathVariable UUID intentId) {
        PaymentIntent intent = payments.resolvePublic(intentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        Tenant tenant = tenants.findById(intent.getTenantId())
                .orElseThrow(() -> new NotFoundException("Business not found"));
        return ApiResponse.ok(PublicIntentDto.of(intent, tenant));
    }

    @PostMapping("/{intentId}/confirm")
    public ApiResponse<PublicIntentDto> confirm(@PathVariable UUID intentId,
                                                @Valid @RequestBody ConfirmRequest req) {
        PaymentIntent intent = payments.confirmPublic(intentId, req.senderBank(), req.senderAccountNumber());
        Tenant tenant = tenants.findById(intent.getTenantId())
                .orElseThrow(() -> new NotFoundException("Business not found"));
        return ApiResponse.ok(PublicIntentDto.of(intent, tenant));
    }

    @PostMapping("/{intentId}/verify")
    public ApiResponse<PublicIntentDto> verify(@PathVariable UUID intentId) {
        PaymentIntent intent = payments.verifyPublic(intentId);
        Tenant tenant = tenants.findById(intent.getTenantId())
                .orElseThrow(() -> new NotFoundException("Business not found"));
        return ApiResponse.ok(PublicIntentDto.of(intent, tenant));
    }

    @GetMapping("/banks")
    public ResponseEntity<ApiResponse<List<PaymentProvider.BankOption>>> banks(
            @RequestParam(defaultValue = "importapay") String provider) {
        // 6h browser cache — the CBN bank list is essentially static.
        // Complements the 6h in-memory cache on ImportapayProvider so
        // even the first browser to hit us serves out of cache on the
        // repeat visit within the same day.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .body(ApiResponse.ok(payments.supportedBanks(provider)));
    }

    // ----- wire shape ------------------------------------------------------

    public record ConfirmRequest(
            @NotBlank String senderBank,
            @NotBlank String senderAccountNumber) {}

    public record PublicIntentDto(
            UUID id,
            String status,
            long amountKobo,
            String currency,
            String provider,
            String origin,
            String originReference,
            // Receiving-account block — what the customer transfers to.
            String receivingBankName,
            String receivingAccountNumber,
            String receivingAccountName,
            // Failure surface.
            String failureReason,
            String matchedTransactionRef,
            // Business branding for the pay page.
            BusinessDto business,
            BrandDto brand) {
        static PublicIntentDto of(PaymentIntent i, Tenant t) {
            return new PublicIntentDto(
                    i.getId(),
                    i.getStatus(),
                    i.getAmountKobo(),
                    i.getCurrency(),
                    i.getProvider(),
                    i.getOrigin(),
                    i.getOriginReference(),
                    i.getReceivingBankName(),
                    i.getReceivingAccountNumber(),
                    i.getReceivingAccountName(),
                    i.getFailureReason(),
                    i.getMatchedTransactionRef(),
                    new BusinessDto(t.getName(), t.getSlug(), t.getContactEmail()),
                    new BrandDto(t.getLogoUrl(), t.getPrimaryColor(), t.getSecondaryColor()));
        }
    }

    public record BusinessDto(String name, String slug, String contactEmail) {}
    public record BrandDto(String logoUrl, String primaryColor, String secondaryColor) {}
}
