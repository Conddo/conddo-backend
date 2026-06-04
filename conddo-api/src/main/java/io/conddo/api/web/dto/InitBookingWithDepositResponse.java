package io.conddo.api.web.dto;

import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.service.BookingService;

/**
 * What the FE gets back from {@code POST /bookings/init-with-deposit}: the
 * new booking (PENDING_DEPOSIT) plus the payment URL it should redirect the
 * customer to. {@code payment} is null when the payments service was
 * unreachable — the booking exists but the FE should surface a retry button
 * that calls {@code POST /bookings/:id/retry-deposit-link} (future endpoint).
 */
public record InitBookingWithDepositResponse(BookingEvent booking, PaymentSummary payment) {

    public static InitBookingWithDepositResponse from(BookingService.InitWithDepositResult result) {
        return new InitBookingWithDepositResponse(BookingEvent.from(result.booking()),
                result.payment() == null ? null : PaymentSummary.from(result.payment()));
    }

    public record PaymentSummary(String reference, String paymentUrl, String status) {
        public static PaymentSummary from(PaymentsGateway.PaymentInitResult init) {
            return new PaymentSummary(init.reference(), init.paymentUrl(), init.status());
        }
    }
}
