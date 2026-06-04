package io.conddo.api.payments;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.BookingService;
import io.conddo.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Callback from conddo-payments after a RoutePay webhook lands. The payments
 * service POSTs here with the result of the verified, deduped webhook so the
 * platform can flip the originating order / booking to PAID, fire the in-app
 * notification, and update tenant ledger.
 *
 * <p>Authenticated by the same {@code X-Payments-Service-Token} the platform
 * uses going the other direction — symmetric service-to-service trust. Same
 * value as {@code PAYMENTS_SERVICE_TOKEN}; checked manually here (rather than
 * via a Security filter) because this controller belongs to conddo-api's
 * security chain which is JWT-first, and adding a second filter for one
 * endpoint isn't worth the noise.
 */
@RestController
@RequestMapping("/api/v1/internal/payments")
public class InternalPaymentsCallbackController {

    public static final String SERVICE_TOKEN_HEADER = "X-Payments-Service-Token";
    private static final Logger log = LoggerFactory.getLogger(InternalPaymentsCallbackController.class);

    private final BookingService bookingService;
    private final String serviceToken;

    public InternalPaymentsCallbackController(BookingService bookingService,
                                              @Value("${payments.service-token:}") String serviceToken) {
        this.bookingService = bookingService;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @PostMapping("/notify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> notify(
            HttpServletRequest request,
            @Valid @RequestBody PaymentNotifyRequest body) {

        if (serviceToken.isBlank() || !serviceToken.equals(request.getHeader(SERVICE_TOKEN_HEADER))) {
            log.warn("Rejected payments callback — bad or missing {}", SERVICE_TOKEN_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.fail(io.conddo.core.common.ApiError.of("UNAUTHENTICATED",
                            "Missing or invalid " + SERVICE_TOKEN_HEADER)));
        }

        // Bind tenant context so RLS lets us update the booking / order.
        TenantContext.set(body.tenantId());
        try {
            if ("PAID".equalsIgnoreCase(body.status()) && body.bookingId() != null) {
                bookingService.markDepositPaid(body.bookingId());
                log.info("Booking {} marked DEPOSIT_PAID (payment {})", body.bookingId(), body.paymentId());
            } else if ("PAID".equalsIgnoreCase(body.status()) && body.orderId() != null) {
                // Order-payment hook is a follow-up; for V1 we just log so the
                // op team can reconcile manually.
                log.info("Order payment notify received (order {}, payment {}) — wiring pending",
                        body.orderId(), body.paymentId());
            } else {
                log.info("Payment notify {} ignored (status={}, no booking/order id)",
                        body.paymentId(), body.status());
            }
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "received", true,
                    "paymentId", body.paymentId(),
                    "status", body.status())));
        } catch (RuntimeException ex) {
            log.error("Failed to process payment notify {}: {}", body.paymentId(), ex.getMessage());
            // 200 anyway so payments doesn't retry forever; ops monitors the log.
            return ResponseEntity.ok(ApiResponse.ok(Map.of("received", false, "error", ex.getMessage())));
        } finally {
            TenantContext.clear();
        }
    }

    /** Body of the notify-back from conddo-payments. */
    public record PaymentNotifyRequest(@NotNull UUID tenantId,
                                       @NotNull UUID paymentId,
                                       @NotBlank String status,
                                       UUID orderId,
                                       UUID bookingId,
                                       long amountKobo) {
    }
}
