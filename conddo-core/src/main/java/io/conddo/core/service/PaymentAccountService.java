package io.conddo.core.service;

import io.conddo.core.domain.TenantPaymentAccount;
import io.conddo.core.repository.TenantPaymentAccountRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant-facing payment-account service. Handles bank connect + KYC
 * submission. Approvals happen on the admin side via
 * {@link AdminKycService} — a tenant can never approve their own KYC.
 *
 * <p>Every method here runs tenant-scoped so RLS keeps one tenant's
 * account isolated from another's.
 */
@Service
public class PaymentAccountService {

    private final TenantPaymentAccountRepository accounts;

    public PaymentAccountService(TenantPaymentAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Get or lazily create the current tenant's payment-account row. */
    @TenantScoped
    public TenantPaymentAccount getOrCreate() {
        UUID tenantId = TenantContext.require();
        return accounts.findById(tenantId).orElseGet(() -> {
            TenantPaymentAccount fresh = new TenantPaymentAccount();
            fresh.setTenantId(tenantId);
            return accounts.save(fresh);
        });
    }

    /**
     * Update the tenant's bank settlement destination. The
     * {@code accountName} comes from the provider name-enquiry step
     * (Phase 2 wires that call; for now we trust what's passed in and
     * stamp {@code accountVerifiedAt}).
     */
    @TenantScoped
    public TenantPaymentAccount updateBankAccount(String bankCode,
                                                  String bankName,
                                                  String accountNumber,
                                                  String accountName) {
        TenantPaymentAccount acct = getOrCreate();
        acct.setBankCode(bankCode);
        acct.setBankName(bankName);
        acct.setAccountNumber(accountNumber);
        acct.setAccountName(accountName);
        acct.setAccountVerifiedAt(OffsetDateTime.now());
        return accounts.save(acct);
    }

    /**
     * Save uploaded KYC document URLs. Does NOT move the KYC into
     * review — the tenant must explicitly {@link #submitForReview()}
     * so admin work isn't triggered by draft uploads.
     */
    @TenantScoped
    public TenantPaymentAccount saveKycDocs(String cacUrl,
                                            String directorIdUrl,
                                            String utilityBillUrl,
                                            String businessAddress) {
        TenantPaymentAccount acct = getOrCreate();
        if (cacUrl != null) acct.setKycCacDocumentUrl(cacUrl);
        if (directorIdUrl != null) acct.setKycDirectorIdUrl(directorIdUrl);
        if (utilityBillUrl != null) acct.setKycUtilityBillUrl(utilityBillUrl);
        if (businessAddress != null) acct.setKycBusinessAddress(businessAddress);
        return accounts.save(acct);
    }

    /**
     * Move the KYC into {@code under_review}. Enforces that all
     * required documents + business address are present, and that a
     * bank account is on file — otherwise there's nothing meaningful
     * for admin to review.
     */
    @TenantScoped
    public TenantPaymentAccount submitForReview() {
        TenantPaymentAccount acct = getOrCreate();
        require(acct.getKycCacDocumentUrl(), "CAC document is required");
        require(acct.getKycDirectorIdUrl(), "Director ID is required");
        require(acct.getKycUtilityBillUrl(), "Utility bill is required");
        require(acct.getKycBusinessAddress(), "Business address is required");
        require(acct.getAccountNumber(), "Bank account is required");
        acct.submitForReview();
        return accounts.save(acct);
    }

    private static void require(String v, String message) {
        if (v == null || v.isBlank()) {
            // Uses IllegalArgumentException so GlobalExceptionHandler
            // maps it to 409 CONFLICT with the reason string surfaced
            // to the tenant. IllegalStateException would fall through
            // to a generic 500.
            throw new IllegalArgumentException(message);
        }
    }
}
