package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.service.PaymentIntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/links")
public class PaymentLinkController {

    private final PaymentIntentService service;

    public PaymentLinkController(PaymentIntentService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest req) {
        PaymentIntent intent = service.createAndInitiate(new PaymentIntentService.NewIntent(
                PaymentIntent.PROVIDER_IMPORTAPAY,
                PaymentIntent.ORIGIN_LINK,
                req.amountKobo(),
                req.currency() != null ? req.currency() : "NGN",
                null,
                req.customerName(),
                req.customerEmail(),
                req.customerPhone(),
                null,
                null,
                null,
                null,
                req.description(),
                null,
                null
        ));
        return ApiResponse.ok(CreateLinkResponse.from(intent));
    }

    public record CreateLinkRequest(
            @NotNull @Min(1) long amountKobo,
            String currency,
            String customerName,
            String customerEmail,
            String customerPhone,
            String description) {}

    public record CreateLinkResponse(
            UUID intentId,
            String status,
            String paymentUrl,
            String receivingBankName,
            String receivingAccountNumber,
            String receivingAccountName,
            long amountKobo,
            String currency,
            OffsetDateTime createdAt) {
        static CreateLinkResponse from(PaymentIntent i) {
            return new CreateLinkResponse(
                    i.getId(),
                    i.getStatus(),
                    "/pay/" + i.getId(),
                    i.getReceivingBankName(),
                    i.getReceivingAccountNumber(),
                    i.getReceivingAccountName(),
                    i.getAmountKobo(),
                    i.getCurrency(),
                    i.getCreatedAt());
        }
    }
}
