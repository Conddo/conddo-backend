package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.IncomingTransfer;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.TenantIntegration;
import io.conddo.core.integrations.MoniepointGateway;
import io.conddo.core.repository.IncomingTransferRepository;
import io.conddo.core.repository.TenantIntegrationRepository;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The money feed (Moniepoint/OPay model): lists inbound transfers from
 * connected accounts, matches them to invoices / orders (idempotent),
 * ingests provider webhooks, and drives the poller sync.
 *
 * <p>Matching is the heart of the design. The tenant's customers pay by
 * transfer into the tenant's OWN Moniepoint / OPay account; Conddo's
 * job is to show the money in {@code /transfers/incoming} and let the
 * tenant confirm which invoice it settles. Confirming marks the invoice
 * paid server-side and issues the receipt (the paid invoice row) — the
 * same thing the app does locally, so it must be idempotent: a second
 * match call returns the existing match instead of double-paying.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final IncomingTransferRepository transfers;
    private final TenantIntegrationRepository integrations;
    private final IntegrationService integrationService;
    private final InvoiceService invoiceService;
    private final OrderService orderService;
    private final MoniepointGateway moniepointGateway;
    private final TenantSession tenantSession;

    public TransferService(IncomingTransferRepository transfers,
                           TenantIntegrationRepository integrations,
                           IntegrationService integrationService,
                           InvoiceService invoiceService,
                           OrderService orderService,
                           MoniepointGateway moniepointGateway,
                           TenantSession tenantSession) {
        this.transfers = transfers;
        this.integrations = integrations;
        this.integrationService = integrationService;
        this.invoiceService = invoiceService;
        this.orderService = orderService;
        this.moniepointGateway = moniepointGateway;
        this.tenantSession = tenantSession;
    }

    // ----- the money feed ----------------------------------------------------

    /** Incoming transfers, newest first. {@code status} ∈ unmatched | matched | all. */
    @Transactional(readOnly = true)
    public List<IncomingTransfer> list(String status) {
        tenantSession.bind();
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return transfers.findAllByOrderByReceivedAtDesc();
        }
        return transfers.findByStatusOrderByReceivedAtDesc(status);
    }

    /**
     * Confirm a match to an invoice or order. Idempotent: an already-
     * matched transfer returns the existing match ({@code alreadyMatched}
     * on the wire) and never re-fires the invoice payment.
     */
    @Transactional
    public MatchResult match(UUID transferId, UUID invoiceId, UUID orderId,
                             String note, UUID userId) {
        tenantSession.bind();
        IncomingTransfer transfer = transfers.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Transfer not found: " + transferId));

        if (IncomingTransfer.STATUS_MATCHED.equals(transfer.getStatus())) {
            return new MatchResult(transfer, true, receiptFor(transfer));
        }
        if ((invoiceId == null) == (orderId == null)) {
            throw new IllegalArgumentException("Provide exactly one of invoiceId or orderId");
        }

        Receipt receipt;
        if (invoiceId != null) {
            receipt = matchToInvoice(transfer, invoiceId);
        } else {
            receipt = matchToOrder(transfer, orderId, note);
        }

        transfer.markMatched(invoiceId, orderId, userId, note);
        transfers.save(transfer);
        return new MatchResult(transfer, false, receipt);
    }

    private Receipt matchToInvoice(IncomingTransfer transfer, UUID invoiceId) {
        Invoice invoice = invoiceService.get(invoiceId);
        if (transfer.getAmountKobo() < invoice.getTotalKobo()) {
            throw new IllegalArgumentException(
                    "Transfer amount (" + naira(transfer.getAmountKobo())
                            + ") is less than the invoice total (" + naira(invoice.getTotalKobo())
                            + "). Ask the customer to top up or match a different transfer.");
        }
        invoiceService.markPaidByTransfer(invoiceId, transfer.getProviderReference(),
                transfer.getReceivedAt());
        return new Receipt("invoice", invoice.getId(), invoice.getInvoiceNumber(), Invoice.STATUS_PAID);
    }

    private Receipt matchToOrder(IncomingTransfer transfer, UUID orderId, String note) {
        BigDecimal amount = BigDecimal.valueOf(transfer.getAmountKobo()).movePointLeft(2);
        String paymentNote = "Matched to " + transfer.getProviderReference()
                + (note == null || note.isBlank() ? "" : " — " + note);
        orderService.addPayment(orderId, amount, "transfer", paymentNote);
        return new Receipt("order", orderId, null, "paid");
    }

    private Receipt receiptFor(IncomingTransfer transfer) {
        if (transfer.getMatchedInvoiceId() != null) {
            try {
                Invoice invoice = invoiceService.get(transfer.getMatchedInvoiceId());
                return new Receipt("invoice", invoice.getId(), invoice.getInvoiceNumber(),
                        invoice.getStatus());
            } catch (RuntimeException ex) {
                // Invoice vanished — degrade to a bare reference.
            }
            return new Receipt("invoice", transfer.getMatchedInvoiceId(), null, "paid");
        }
        if (transfer.getMatchedOrderId() != null) {
            return new Receipt("order", transfer.getMatchedOrderId(), null, "paid");
        }
        return null;
    }

    // ----- webhook ingest ----------------------------------------------------

    /**
     * Ingest a provider webhook payload into the feed. Cross-tenant: the
     * merchant reference in the payload resolves the owning tenant, and
     * (provider, providerReference) dedupes retries. Returns true when a
     * new transfer row was created, false for a duplicate or an
     * unroutable payload (the caller logs and acks).
     */
    @Transactional
    public boolean ingestWebhook(String provider, String rawJson, JsonNode payload) {
        tenantSession.bindCrossTenant();

        String merchantRef = resolveMerchantReference(provider, payload);
        if (merchantRef == null) {
            log.warn("{} webhook: no merchant reference in payload to route on", provider);
            return false;
        }
        TenantIntegration integration = integrations
                .findByProviderAndMerchantReference(provider, merchantRef).orElse(null);
        if (integration == null) {
            log.warn("{} webhook: no connected account for merchant ref {}", provider, merchantRef);
            return false;
        }

        TransferRecord rec = extractTransfer(provider, payload);
        if (rec == null || rec.providerReference() == null) {
            log.debug("{} webhook: no payable transaction in payload (event {}), acked",
                    provider, payload.path("event").asText(""));
            return false;
        }
        if (transfers.findByProviderAndProviderReference(provider, rec.providerReference()).isPresent()) {
            return false;   // retry of an already-ingested event
        }

        IncomingTransfer transfer = new IncomingTransfer(
                integration.getTenantId(), provider, rec.providerReference(),
                rec.amountKobo(), rec.currency(), rec.receivedAt());
        transfer.setSenderName(rec.senderName());
        transfer.setSenderBank(rec.senderBank());
        transfer.setSenderAccountNumber(rec.senderAccountNumber());
        transfer.setRawPayload(rawJson);
        transfers.save(transfer);
        return true;
    }

    // ----- poller sync -------------------------------------------------------

    /**
     * Pull paid transactions from every connected Moniepoint account and
     * upsert them into the feed. OPay is webhook-primary (no documented
     * list endpoint) so its accounts are skipped. Returns the number of
     * new transfers created. Cross-tenant — safe to run from the
     * scheduler with no request context.
     */
    @Transactional
    public int syncConnectedAccounts() {
        tenantSession.bindCrossTenant();
        int created = 0;
        // Include errored accounts so a transient failure doesn't drop the
        // account from the sync loop — the loop below flips them back to
        // connected on the next successful run.
        for (TenantIntegration integration :
                integrations.findByProviderAndStatusIn(TenantIntegration.PROVIDER_MONIEPOINT,
                        java.util.List.of(TenantIntegration.STATUS_CONNECTED,
                                TenantIntegration.STATUS_ERROR))) {
            try {
                String apiKey = integrationService.decryptCredential(integration, "apiKey");
                if (apiKey == null || apiKey.isBlank()) {
                    integration.markError("Stored Moniepoint key could not be decrypted");
                    integrations.save(integration);
                    continue;
                }
                OffsetDateTime since = integration.getLastCheckedAt() == null
                        ? OffsetDateTime.now().minusDays(7)
                        : integration.getLastCheckedAt().minusHours(2);   // overlap for late arrivals
                List<MoniepointGateway.TransferRecord> records =
                        moniepointGateway.fetchTransactions(apiKey, since);
                for (MoniepointGateway.TransferRecord r : records) {
                    if (transfers.findByProviderAndProviderReference(
                            TenantIntegration.PROVIDER_MONIEPOINT, r.providerReference()).isEmpty()) {
                        IncomingTransfer transfer = new IncomingTransfer(
                                integration.getTenantId(), TenantIntegration.PROVIDER_MONIEPOINT,
                                r.providerReference(), r.amountKobo(), r.currency(), r.receivedAt());
                        transfer.setSenderName(r.senderName());
                        transfer.setSenderBank(r.senderBank());
                        transfer.setSenderAccountNumber(r.senderAccountNumber());
                        transfer.setRawPayload(r.rawJson());
                        transfers.save(transfer);
                        created++;
                    }
                }
                integration.setLastCheckedAt(OffsetDateTime.now());
                integration.setLastError(null);
                if (!TenantIntegration.STATUS_CONNECTED.equals(integration.getStatus())) {
                    integration.setStatus(TenantIntegration.STATUS_CONNECTED);
                }
                integrations.save(integration);
            } catch (RuntimeException ex) {
                log.warn("Moniepoint sync failed for integration {}: {}",
                        integration.getId(), ex.getMessage());
                integration.markError(ex.getMessage());
                integrations.save(integration);
            }
        }
        if (created > 0) {
            log.info("Transfer sync: {} new incoming transfers", created);
        }
        return created;
    }

    // ----- payload parsing ---------------------------------------------------

    /** Which merchant sent this webhook — resolves the tenant. */
    private String resolveMerchantReference(String provider, JsonNode payload) {
        if (TenantIntegration.PROVIDER_MONIEPOINT.equals(provider)) {
            // Moniepoint transaction records carry the merchant's api key —
            // we match on its hash, never the raw key.
            String apiKey = firstText(payload,
                    "eventData.apiKey", "data.apiKey", "apiKey");
            if (apiKey != null && !apiKey.isBlank()) {
                return IntegrationService.sha256Hex(apiKey);
            }
            return firstText(payload,
                    "eventData.merchantReference", "data.merchantReference", "merchantReference");
        }
        return firstText(payload, "data.merchantId", "merchantId", "eventData.merchantId");
    }

    /** Extract the payable transaction from a webhook payload, tolerant of shape. */
    private TransferRecord extractTransfer(String provider, JsonNode payload) {
        String reference;
        long amountKobo;
        OffsetDateTime receivedAt;
        if (TenantIntegration.PROVIDER_MONIEPOINT.equals(provider)) {
            reference = firstText(payload,
                    "eventData.transactionReference", "data.transactionReference",
                    "eventData.paymentReference", "data.paymentReference");
            amountKobo = firstLong(payload, "eventData.amount", "data.amount");
            receivedAt = firstInstant(payload,
                    "eventData.paidOn", "data.paidOn",
                    "eventData.transactionDate", "data.transactionDate",
                    "eventData.createdAt", "data.createdAt");
        } else {
            reference = firstText(payload,
                    "data.reference", "data.transactionReference", "data.orderNo",
                    "eventData.reference", "eventData.transactionReference");
            amountKobo = firstLong(payload, "data.amount", "eventData.amount");
            receivedAt = firstInstant(payload,
                    "data.paidAt", "data.completedAt", "data.createdAt",
                    "eventData.paidAt", "eventData.completedAt");
        }
        if (reference == null || amountKobo <= 0) {
            return null;
        }
        String senderName = firstText(payload,
                "eventData.customerName", "data.customer.name",
                "eventData.payerName", "data.payerName", "data.customerName");
        String senderBank = firstText(payload,
                "eventData.senderBank", "data.senderBank",
                "eventData.customerBank", "data.customerBank",
                "data.customer.bankName");
        String senderAccount = firstText(payload,
        "eventData.senderAccountNumber", "data.senderAccountNumber",
        "eventData.customerAccountNumber", "data.customerAccountNumber",
        "data.customer.accountNumber");
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now();
        }
        return new TransferRecord(reference, senderName, senderAccount, senderBank,
                amountKobo, "NGN", receivedAt);
    }

    // ----- wire shapes -------------------------------------------------------

    public record MatchResult(IncomingTransfer transfer, boolean alreadyMatched, Receipt receipt) {
    }

    /** The receipt issued by a match - the paid invoice row, or the recorded order payment. */
    public record Receipt(String type, UUID id, String reference, String status) {
    }

    /** Normalised inbound transaction from a webhook payload. */
    private record TransferRecord(String providerReference, String senderName,
                                  String senderAccountNumber, String senderBank,
                                  long amountKobo, String currency, OffsetDateTime receivedAt) {
    }

    // ----- helpers -----------------------------------------------------------

    private static String naira(long kobo) {
        return "\u20A6" + BigDecimal.valueOf(kobo).movePointLeft(2).toPlainString();
    }

    /** First non-null text across dotted paths. */
    private static String firstText(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            boolean found = true;
            for (String part : path.split("\\.")) {
                node = node.path(part);
                if (node.isMissingNode() || node.isNull()) {
                    found = false;
                    break;
                }
            }
            if (found && !node.isContainerNode()) {
                String v = node.asText(null);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    /** First numeric value across dotted paths. */
    private static long firstLong(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            boolean found = true;
            for (String part : path.split("\\.")) {
                node = node.path(part);
                if (node.isMissingNode() || node.isNull()) {
                    found = false;
                    break;
                }
            }
            if (found && node.isNumber()) {
                return node.asLong(0);
            }
        }
        return 0;
    }

    /** First parseable instant across dotted paths. */
    private static OffsetDateTime firstInstant(JsonNode root, String... paths) {
        String raw = firstText(root, paths);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
