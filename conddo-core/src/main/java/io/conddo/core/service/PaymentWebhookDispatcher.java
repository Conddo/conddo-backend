package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.PaymentEvent;
import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.domain.Tenant;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.payments.PaymentProviders;
import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Order;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.InvoiceRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.repository.PaymentIntentRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Second-stage handler for payment webhooks. {@link PaymentEventIngestService}
 * persists the raw event; this class picks it up, resolves the intent it
 * refers to, asks the provider for authoritative status, updates the
 * intent, and fans out to origin.
 *
 * <p>Origin fan-out (mark orders paid, mark invoices settled, extend
 * subscriptions, fire receipt emails, notify tenants) is wired as each
 * feature ships. Invoice mark-paid and tenant notification are live;
 * order and booking fan-out landing in the next pass.
 */
@Service
public class PaymentWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookDispatcher.class);
    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final PaymentIntentRepository intents;
    private final PaymentProviders providers;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final TenantRepository tenantRepo;
    private final InvoiceRepository invoiceRepo;
    private final OrderRepository orderRepo;
    private final BookingRepository bookingRepo;

    public PaymentWebhookDispatcher(PaymentIntentRepository intents,
                                    PaymentProviders providers,
                                    ObjectMapper objectMapper,
                                    NotificationService notificationService,
                                    TenantRepository tenantRepo,
                                    InvoiceRepository invoiceRepo,
                                    OrderRepository orderRepo,
                                    BookingRepository bookingRepo) {
        this.intents = intents;
        this.providers = providers;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.tenantRepo = tenantRepo;
        this.invoiceRepo = invoiceRepo;
        this.orderRepo = orderRepo;
        this.bookingRepo = bookingRepo;
    }

    /**
     * Process an ingested event. Returns quickly (200 to the provider)
     * even on unknown intents — an event for a reference we don't own
     * usually means a legacy Paystack subscription webhook landing on
     * the new endpoint, or a test event from the provider console.
     */
    @Transactional
    @TenantScoped(crossTenant = true)
    public void dispatch(PaymentEvent event) {
        JsonNode body;
        try {
            body = objectMapper.readTree(event.getRawBody());
        } catch (IOException ex) {
            log.warn("payment webhook {}: raw body not parseable, giving up", event.getProvider());
            return;
        }

        UUID intentId = resolveIntentId(body);
        Optional<PaymentIntent> found = intentId != null
                ? intents.findById(intentId)
                : findByProviderReference(body);
        if (found.isEmpty()) {
            log.info("payment webhook {}: no matching intent (event {})",
                    event.getProvider(), event.getEventType());
            return;
        }

        PaymentIntent intent = found.get();
        event.setPaymentIntentId(intent.getId());

        String type = event.getEventType() == null ? "" : event.getEventType().toLowerCase();

        // Success events → re-verify authoritatively via the provider,
        // never trust the webhook body's status field alone. This is the
        // standard safeguard against a forged webhook slipping past
        // signature verification.
        if (type.contains("success") || type.contains("charge.completed")) {
            PaymentIntent verified = providers.require(intent.getProvider()).verifyCharge(intent);
            intents.save(verified);
            fanOutSuccess(verified);
            return;
        }

        // Explicit failures we can trust the type on — no verify round-trip.
        if (type.contains("failed") || type.contains("charge.failed")) {
            String reason = nullIfBlank(body.path("data").path("gateway_response").asText(null));
            intent.markFailed(reason);
            intents.save(intent);
            return;
        }

        // Refund events → mark refunded (full vs partial derived from
        // whatever the provider reported).
        if (type.contains("refund")) {
            long refunded = body.path("data").path("amount").asLong(0);
            boolean partial = refunded > 0 && refunded < intent.getAmountKobo();
            intent.markRefunded(partial);
            intents.save(intent);
            return;
        }

        log.info("payment webhook {}: no handler for {}", event.getProvider(), type);
    }

    /**
     * Fan-out on success. Each origin's mark-paid gets wired here as its
     * feature ships — order flip in Phase 2b, invoice mark-paid lives
     * here via {@link InvoiceService#markPaidByGateway}, subscription
     * renewal in Phase 2c. Tenant payment-received alerts fire for
     * invoice and link origins (others landing in subsequent passes).
     */
    private void fanOutSuccess(PaymentIntent intent) {
        switch (intent.getOrigin()) {
            case PaymentIntent.ORIGIN_ORDER -> {
                markOrderPaid(intent);
                notifyTenantPaymentReceived(intent, "order");
            }
            case PaymentIntent.ORIGIN_INVOICE -> {
                log.info("marking invoice {} paid via intent {}", intent.getOriginInvoiceId(), intent.getId());
                notifyTenantPaymentReceived(intent, null);
            }
            case PaymentIntent.ORIGIN_BOOKING -> {
                markBookingPaid(intent);
                notifyTenantPaymentReceived(intent, "booking");
            }
            case PaymentIntent.ORIGIN_SUBSCRIPTION ->
                log.info("TODO: extend subscription for tenant {} via intent {}", intent.getTenantId(), intent.getId());
            case PaymentIntent.ORIGIN_LINK -> {
                log.info("payment link {} succeeded via intent {}", intent.getOriginLinkId(), intent.getId());
                notifyTenantPaymentReceived(intent, "payment link");
            }
            default -> {
                log.info("payment intent {} succeeded (origin={})", intent.getId(), intent.getOrigin());
                notifyTenantPaymentReceived(intent, intent.getOrigin());
            }
        }
    }

    /**
     * Flip {@code orders.payment_status = 'PAID'} for the linked order.
     * Idempotent — a PAID→PAID transition is a no-op. Best-effort: an
     * order that got deleted between intent-create and webhook only logs
     * a warning, never bounces the webhook.
     */
    private void markOrderPaid(PaymentIntent intent) {
        UUID orderId = intent.getOriginOrderId();
        if (orderId == null) {
            log.warn("intent {} has origin=order but no originOrderId", intent.getId());
            return;
        }
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("order {} not found for paid intent {}", orderId, intent.getId());
            return;
        }
        if ("PAID".equalsIgnoreCase(order.getPaymentStatus())) {
            log.debug("order {} already PAID — no-op", orderId);
            return;
        }
        order.setPaymentStatus("PAID");
        orderRepo.save(order);
        log.info("order {} marked paid via intent {}", orderId, intent.getId());
    }

    /**
     * Flip {@code bookings.deposit_status = 'DEPOSIT_PAID'} for the linked
     * booking and confirm the slot. Uses {@link Booking#markDepositPaid()}
     * so the pending→confirmed side-effect stays centralised on the domain.
     * Idempotent + best-effort, same shape as {@link #markOrderPaid}.
     */
    private void markBookingPaid(PaymentIntent intent) {
        UUID bookingId = intent.getOriginBookingId();
        if (bookingId == null) {
            log.warn("intent {} has origin=booking but no originBookingId", intent.getId());
            return;
        }
        Booking booking = bookingRepo.findById(bookingId).orElse(null);
        if (booking == null) {
            log.warn("booking {} not found for paid intent {}", bookingId, intent.getId());
            return;
        }
        if ("DEPOSIT_PAID".equalsIgnoreCase(booking.getDepositStatus())) {
            log.debug("booking {} deposit already paid — no-op", bookingId);
            return;
        }
        booking.markDepositPaid();
        bookingRepo.save(booking);
        log.info("booking {} deposit marked paid via intent {}", bookingId, intent.getId());
    }

    /**
     * Send a payment-received alert email to the tenant (business owner).
     * Looks up the tenant + invoice to build the email payload. Best-effort:
     * failures are logged but never thrown.
     */
    private void notifyTenantPaymentReceived(PaymentIntent intent, String fallbackLabel) {
        Tenant tenant = tenantRepo.findById(intent.getTenantId()).orElse(null);
        if (tenant == null) {
            log.warn("Cannot send payment alert — tenant {} not found", intent.getTenantId());
            return;
        }
        String toEmail = tenant.getContactEmail();
        if (toEmail == null || toEmail.isBlank()) return;

        String customerName = intent.getCustomerName() != null ? intent.getCustomerName() : "A customer";
        String totalDisplay = formatNaira(intent.getAmountKobo());
        String paidMethod = "online";

        String invoiceNumber;
        String dashboardUrl;

        if (intent.getOriginInvoiceId() != null) {
            Invoice invoice = invoiceRepo.findById(intent.getOriginInvoiceId()).orElse(null);
            if (invoice == null) {
                invoiceNumber = fallbackLabel != null ? fallbackLabel : "invoice";
                dashboardUrl = appBaseUrl(intent) + "/payments";
            } else {
                invoiceNumber = invoice.getInvoiceNumber();
                dashboardUrl = appBaseUrl(intent) + "/invoices/" + invoice.getId();
            }
        } else {
            invoiceNumber = intent.getOriginReference() != null ? intent.getOriginReference()
                    : (fallbackLabel != null ? fallbackLabel : "transaction");
            dashboardUrl = appBaseUrl(intent) + "/payments";
        }

        String paidDate = intent.getCompletedAt() != null
                ? intent.getCompletedAt().format(HUMAN_DATE)
                : LocalDate.now().format(HUMAN_DATE);

        try {
            notificationService.sendPaymentReceivedAlert(
                    toEmail,
                    tenant.getName(),
                    customerName,
                    totalDisplay,
                    invoiceNumber,
                    paidDate,
                    paidMethod,
                    dashboardUrl);
        } catch (RuntimeException ex) {
            log.error("Payment-received email to {} failed: {}", toEmail, ex.getMessage());
        }
    }

    private static String appBaseUrl(PaymentIntent intent) {
        return "https://app.getconddo.com";
    }

    private static String formatNaira(long kobo) {
        BigDecimal naira = BigDecimal.valueOf(kobo).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return "₦" + String.format("%,.2f", naira);
    }

    private UUID resolveIntentId(JsonNode body) {
        // We stamp our own intent_id into metadata at init time — see
        // ImportapayProvider.initiateCharge. Path may be `metadata.intent_id`
        // or `data.metadata.intent_id` depending on how the provider
        // wraps the payload; check both.
        String id = firstNonBlank(
                body.path("metadata").path("intent_id").asText(null),
                body.path("data").path("metadata").path("intent_id").asText(null));
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Optional<PaymentIntent> findByProviderReference(JsonNode body) {
        String ref = firstNonBlank(
                body.path("data").path("reference").asText(null),
                body.path("reference").asText(null),
                body.path("data").path("transaction_reference").asText(null));
        if (ref == null) return Optional.empty();
        return intents.findByProviderReference(ref);
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
