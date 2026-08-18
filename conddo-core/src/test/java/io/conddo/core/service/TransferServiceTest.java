package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.IncomingTransfer;
import io.conddo.core.domain.Invoice;
import io.conddo.core.domain.TenantIntegration;
import io.conddo.core.integrations.MoniepointGateway;
import io.conddo.core.repository.IncomingTransferRepository;
import io.conddo.core.repository.TenantIntegrationRepository;
import io.conddo.core.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the transfer-matching contract (V77): idempotent match to an
 * invoice (marks paid + issues the receipt), underpayment rejection,
 * and webhook ingestion with (provider, providerReference) dedupe.
 */
class TransferServiceTest {

    private final IncomingTransferRepository transfers = mock(IncomingTransferRepository.class);
    private final TenantIntegrationRepository integrations = mock(TenantIntegrationRepository.class);
    private final IntegrationService integrationService = mock(IntegrationService.class);
    private final InvoiceService invoiceService = mock(InvoiceService.class);
    private final OrderService orderService = mock(OrderService.class);
    private final MoniepointGateway moniepointGateway = mock(MoniepointGateway.class);
    private final TenantSession tenantSession = mock(TenantSession.class);

    private TransferService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new TransferService(transfers, integrations, integrationService,
                invoiceService, orderService, moniepointGateway, tenantSession);
    }

    @Test
    void matchToInvoiceMarksPaidAndIssuesReceipt() {
        UUID tenantId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.now();

        IncomingTransfer transfer = new IncomingTransfer(
                tenantId, "moniepoint", "MP-REF-1", 150_000L, "NGN", receivedAt);
        Invoice invoice = invoice(tenantId, invoiceId, 150_000L);

        when(transfers.findById(transferId)).thenReturn(Optional.of(transfer));
        when(invoiceService.get(invoiceId)).thenReturn(invoice);

        TransferService.MatchResult result = service.match(transferId, invoiceId, null, null, userId);

        assertFalse(result.alreadyMatched());
        assertEquals(IncomingTransfer.STATUS_MATCHED, result.transfer().getStatus());
        assertEquals(invoiceId, result.transfer().getMatchedInvoiceId());
        assertNotNull(result.receipt());
        assertEquals("invoice", result.receipt().type());
        assertEquals("INV-2026-0001", result.receipt().reference());
        verify(invoiceService).markPaidByTransfer(eq(invoiceId), eq("MP-REF-1"), eq(receivedAt));
        verify(transfers).save(transfer);
    }

    @Test
    void matchIsIdempotent() {
        UUID tenantId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();

        IncomingTransfer transfer = new IncomingTransfer(
                tenantId, "moniepoint", "MP-REF-1", 150_000L, "NGN", OffsetDateTime.now());
        transfer.markMatched(invoiceId, null, null, null);

        when(transfers.findById(transferId)).thenReturn(Optional.of(transfer));
        when(invoiceService.get(invoiceId)).thenReturn(invoice(tenantId, invoiceId, 150_000L));

        TransferService.MatchResult result = service.match(transferId, invoiceId, null, null, UUID.randomUUID());

        assertTrue(result.alreadyMatched());
        // Idempotent replay must NOT re-fire the invoice payment.
        verify(invoiceService, never()).markPaidByTransfer(any(), any(), any());
        verify(transfers, never()).save(any());
    }

    @Test
    void matchRejectsUnderpayment() {
        UUID tenantId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();

        IncomingTransfer transfer = new IncomingTransfer(
                tenantId, "moniepoint", "MP-REF-1", 100_000L, "NGN", OffsetDateTime.now());
        when(transfers.findById(transferId)).thenReturn(Optional.of(transfer));
        when(invoiceService.get(invoiceId)).thenReturn(invoice(tenantId, invoiceId, 150_000L));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.match(transferId, invoiceId, null, null, null));
        assertTrue(ex.getMessage().contains("less than the invoice total"));
        verify(invoiceService, never()).markPaidByTransfer(any(), any(), any());
    }

    @Test
    void matchRequiresExactlyOneTarget() {
        UUID transferId = UUID.randomUUID();
        IncomingTransfer transfer = new IncomingTransfer(
                UUID.randomUUID(), "opay", "OPAY-REF-1", 50_000L, "NGN", OffsetDateTime.now());
        when(transfers.findById(transferId)).thenReturn(Optional.of(transfer));

        assertThrows(IllegalArgumentException.class,
                () -> service.match(transferId, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.match(transferId, UUID.randomUUID(), UUID.randomUUID(), null, null));
    }

    @Test
    void matchToOrderRecordsPaymentInNaira() {
        UUID tenantId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        IncomingTransfer transfer = new IncomingTransfer(
                tenantId, "moniepoint", "MP-REF-2", 25_000L, "NGN", OffsetDateTime.now());
        when(transfers.findById(transferId)).thenReturn(Optional.of(transfer));

        TransferService.MatchResult result = service.match(transferId, null, orderId, "seamstress", null);

        assertEquals("order", result.receipt().type());
        assertEquals(orderId, result.transfer().getMatchedOrderId());
        verify(orderService).addPayment(eq(orderId), eq(new BigDecimal("250.00")), eq("transfer"), any());
    }

    @Test
    void ingestWebhookCreatesTransferAndDedupes() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // sha256Hex is static — compute the real lookup key the payload's
        // apiKey resolves to, and store it on the connected account.
        String merchantRef = IntegrationService.sha256Hex("mp_live_abc");
        TenantIntegration integration =
                new TenantIntegration(tenantId, TenantIntegration.PROVIDER_MONIEPOINT);
        integration.setMerchantReference(merchantRef);

        String raw = """
                {"event":"transaction.paid","eventData":{"transactionReference":"MP-TX-99","apiKey":"mp_live_abc","amount":75000,"paidOn":"2026-08-17T10:00:00Z","customerName":"Adaeze Okafor"}}
                """;
        JsonNode payload = objectMapper.readTree(raw);

        when(integrations.findByProviderAndMerchantReference(
                TenantIntegration.PROVIDER_MONIEPOINT, merchantRef))
                .thenReturn(Optional.of(integration));
        when(transfers.findByProviderAndProviderReference(
                TenantIntegration.PROVIDER_MONIEPOINT, "MP-TX-99"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new IncomingTransfer(
                        tenantId, "moniepoint", "MP-TX-99", 75_000L, "NGN", OffsetDateTime.now())));

        boolean created = service.ingestWebhook(TenantIntegration.PROVIDER_MONIEPOINT, raw, payload);
        assertTrue(created);
        verify(transfers).save(any(IncomingTransfer.class));

        // Retry of the same provider reference is deduped.
        boolean again = service.ingestWebhook(TenantIntegration.PROVIDER_MONIEPOINT, raw, payload);
        assertFalse(again);
        verify(transfers).save(any(IncomingTransfer.class));   // still exactly one save
    }

    private static Invoice invoice(UUID tenantId, UUID id, long totalKobo) {
        Invoice invoice = new Invoice(tenantId, "INV-2026-0001", "Adaeze Okafor", "token");
        invoice.applyTotals(totalKobo, 0, 0);
        try {
            java.lang.reflect.Field f = Invoice.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(invoice, id);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return invoice;
    }
}
