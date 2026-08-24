package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.IncomingTransfer;
import io.conddo.core.domain.TenantIntegration;
import io.conddo.core.integrations.MoniepointGateway;
import io.conddo.core.integrations.OpayGateway;
import io.conddo.core.integrations.SecretCipher;
import io.conddo.core.repository.IncomingTransferRepository;
import io.conddo.core.repository.TenantIntegrationRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Connected payment accounts (the Moniepoint/OPay model). Stores the
 * tenant's provider credentials encrypted at rest, verifies them against
 * the provider on connect, and exposes the account list + the
 * "sales brought in / waiting to match" stats.
 *
 * <p>Credentials never leave this service: the controller receives an
 * {@link IntegrationView} that deliberately excludes the credential
 * fields.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    private final TenantIntegrationRepository integrations;
    private final IncomingTransferRepository transfers;
    private final MoniepointGateway moniepointGateway;
    private final OpayGateway opayGateway;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final TenantSession tenantSession;

    public IntegrationService(TenantIntegrationRepository integrations,
                              IncomingTransferRepository transfers,
                              MoniepointGateway moniepointGateway,
                              OpayGateway opayGateway,
                              SecretCipher cipher,
                              ObjectMapper objectMapper,
                              TenantSession tenantSession) {
        this.integrations = integrations;
        this.transfers = transfers;
        this.moniepointGateway = moniepointGateway;
        this.opayGateway = opayGateway;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.tenantSession = tenantSession;
    }

    // ----- connect -----------------------------------------------------------

    /** Store + verify a Moniepoint merchant key; returns the live account view. */
    @Transactional
    public IntegrationView connectMoniepoint(String apiKey) {
        tenantSession.bind();
        String key = apiKey == null ? "" : apiKey.trim();
        if (!key.startsWith("mp_")) {
            throw new IllegalArgumentException(
                    "apiKey must be a Moniepoint key (mp_live_... or mp_test_...) from the Moniepoint dashboard");
        }
        MoniepointGateway.MerchantInfo info = moniepointGateway.verify(key);

        UUID tenantId = TenantContext.require();
        TenantIntegration row = integrations
                .findByTenantIdAndProvider(tenantId, TenantIntegration.PROVIDER_MONIEPOINT)
                .orElseGet(() -> new TenantIntegration(tenantId, TenantIntegration.PROVIDER_MONIEPOINT));

        row.setCredentials(credentialsJson("apiKey", cipher.encrypt(key)));
        String ref = sha256Hex(key);
        String label = info.businessName() != null ? info.businessName() : "Moniepoint account";
        row.markVerified(label, info.businessName(), info.accountName(),
                info.accountNumber(), info.bankName(), info.terminalSerial(), ref);
        return IntegrationView.of(integrations.save(row));
    }

    /**
     * Store + verify OPay merchant credentials. Each field is validated
     * separately (format + live check) so the UI can point at the exact
     * field that's wrong.
     */
    @Transactional
    public IntegrationView connectOpay(String merchantId, String privateKey, String publicKey) {
        tenantSession.bind();
        OpayGateway.MerchantInfo info = opayGateway.verify(merchantId, privateKey, publicKey);

        UUID tenantId = TenantContext.require();
        TenantIntegration row = integrations
                .findByTenantIdAndProvider(tenantId, TenantIntegration.PROVIDER_OPAY)
                .orElseGet(() -> new TenantIntegration(tenantId, TenantIntegration.PROVIDER_OPAY));

        row.setCredentials(credentialsJson(
                "merchantId", cipher.encrypt(trimToNull(merchantId)),
                "privateKey", cipher.encrypt(trimToNull(privateKey)),
                "publicKey", cipher.encrypt(trimToNull(publicKey))));
        String label = info.businessName() != null ? info.businessName() : "OPay account";
        row.markVerified(label, info.businessName(), info.accountName(),
                info.accountNumber(), info.bankName(), info.terminalSerial(),
                merchantId == null ? null : merchantId.trim());
        return IntegrationView.of(integrations.save(row));
    }

    /**
     * Store a tenant's own Paystack secret key so customer payments can
     * route to their Paystack merchant account. Distinct from platform
     * billing (CONDDO_PAYSTACK_SECRET_KEY), which lives in env config.
     *
     * <p>No live verify — Paystack's ping shape is simple and we defer
     * it to first-charge time. Format check only: must start with
     * {@code sk_live_} or {@code sk_test_} so fat-fingered pastes fail
     * loudly instead of silently storing garbage.
     */
    @Transactional
    public IntegrationView connectPaystack(String secretKey) {
        tenantSession.bind();
        String key = secretKey == null ? "" : secretKey.trim();
        if (!key.startsWith("sk_live_") && !key.startsWith("sk_test_")) {
            throw new IllegalArgumentException(
                    "secretKey must be a Paystack key (sk_live_... or sk_test_...) from the Paystack dashboard");
        }

        UUID tenantId = TenantContext.require();
        TenantIntegration row = integrations
                .findByTenantIdAndProvider(tenantId, TenantIntegration.PROVIDER_PAYSTACK)
                .orElseGet(() -> new TenantIntegration(tenantId, TenantIntegration.PROVIDER_PAYSTACK));

        row.setCredentials(credentialsJson("secretKey", cipher.encrypt(key)));
        String ref = sha256Hex(key);
        // No live-verified business snapshot yet — populate the label so
        // the account list has something to render, mark verified so the
        // status pill is green, and let first-charge time correct the
        // business name if Paystack disagrees.
        String label = key.startsWith("sk_test_") ? "Paystack (test)" : "Paystack";
        row.markVerified(label, null, null, null, null, null, ref);
        return IntegrationView.of(integrations.save(row));
    }

    // ----- read --------------------------------------------------------------

    /** The tenant's connected accounts + money-feed stats. */
    @Transactional(readOnly = true)
    public Overview list() {
        tenantSession.bind();
        List<IntegrationView> accounts = integrations.findAllByOrderByCreatedAtDesc()
                .stream().map(IntegrationView::of).toList();
        return new Overview(accounts, stats());
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        tenantSession.bind();
        long broughtIn = transfers.sumAmountKoboByStatusAll();
        long waitingKobo = transfers.sumAmountKoboByStatus(IncomingTransfer.STATUS_UNMATCHED);
        long waitingCount = transfers.countByStatus(IncomingTransfer.STATUS_UNMATCHED);
        return new Stats(broughtIn, waitingKobo, waitingCount);
    }

    /** Soft-disconnect an account - credentials stay so a reconnect can reuse them. */
    @Transactional
    public IntegrationView disconnect(String provider) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        TenantIntegration row = integrations.findByTenantIdAndProvider(tenantId, provider)
                .orElseThrow(() -> new NotFoundException("No " + provider + " account connected"));
        row.markDisconnected();
        return IntegrationView.of(integrations.save(row));
    }

    // ----- credential helpers (used by TransferService + poller) -------------

    /** Decrypt one credential field from a stored integration row. */
    public String decryptCredential(TenantIntegration integration, String field) {
        try {
            JsonNode node = objectMapper.readTree(integration.getCredentials()).path(field);
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return cipher.decrypt(node.asText());
        } catch (java.io.IOException | RuntimeException ex) {
            log.warn("Could not decrypt credential {} for integration {}: {}",
                    field, integration.getId(), ex.getMessage());
            return null;
        }
    }

    /** SHA-256 hex of the Moniepoint api key - the non-secret webhook lookup key. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String credentialsJson(String... kv) {
        ObjectNode node = objectMapper.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            node.put(kv[i], kv[i + 1]);
        }
        return node.toString();
    }

    private static String trimToNull(String v) {
        return v == null ? null : (v.isBlank() ? null : v.trim());
    }

    // ----- wire shapes -------------------------------------------------------

    /** Tenant-facing account row - NEVER includes credentials. */
    public record IntegrationView(
            UUID id, String provider, String status, String label,
            String businessName, String accountName, String accountNumber,
            String bankName, String terminalSerial,
            OffsetDateTime verifiedAt, OffsetDateTime lastCheckedAt, String lastError) {
        static IntegrationView of(TenantIntegration i) {
            return new IntegrationView(
                    i.getId(), i.getProvider(), i.getStatus(), i.getLabel(),
                    i.getBusinessName(), i.getAccountName(), i.getAccountNumber(),
                    i.getBankName(), i.getTerminalSerial(),
                    i.getVerifiedAt(), i.getLastCheckedAt(), i.getLastError());
        }
    }

    public record Overview(List<IntegrationView> accounts, Stats stats) {
    }

    public record Stats(long salesBroughtInKobo, long waitingToMatchKobo, long waitingToMatchCount) {
    }
}
