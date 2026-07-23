package io.conddo.core.service;

import io.conddo.core.domain.TenantPaymentAccount;
import io.conddo.core.repository.TenantPaymentAccountRepository;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Admin-side KYC review. Lists queues, approves, rejects — all
 * cross-tenant since a platform admin needs to see every tenant's
 * pending queue at once.
 *
 * <p>Approval only flips {@code paymentsEnabled} when the bank account
 * is also verified — otherwise the tenant is technically KYC-clean but
 * has nowhere for the money to land.
 */
@Service
public class AdminKycService {

    private final TenantPaymentAccountRepository accounts;

    public AdminKycService(TenantPaymentAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** All accounts awaiting review, oldest submission first. */
    @Transactional
    @TenantScoped(crossTenant = true)
    public List<TenantPaymentAccount> pendingReview() {
        return accounts.findByKycStatusOrderByKycSubmittedAtAsc(TenantPaymentAccount.KYC_UNDER_REVIEW);
    }

    @Transactional
    @TenantScoped(crossTenant = true)
    public TenantPaymentAccount get(UUID tenantId) {
        return accounts.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No payment account for tenant " + tenantId));
    }

    /** Approve. Flips paymentsEnabled iff bank is verified. */
    @Transactional
    @TenantScoped(crossTenant = true)
    public TenantPaymentAccount approve(UUID tenantId, UUID reviewerId) {
        TenantPaymentAccount acct = get(tenantId);
        if (!TenantPaymentAccount.KYC_UNDER_REVIEW.equals(acct.getKycStatus())) {
            throw new IllegalArgumentException("Only under-review accounts can be approved (was " + acct.getKycStatus() + ")");
        }
        acct.approve(reviewerId);
        return accounts.save(acct);
    }

    /** Reject with a reason the tenant will see on their /settings/payments. */
    @Transactional
    @TenantScoped(crossTenant = true)
    public TenantPaymentAccount reject(UUID tenantId, UUID reviewerId, String reason) {
        TenantPaymentAccount acct = get(tenantId);
        if (!TenantPaymentAccount.KYC_UNDER_REVIEW.equals(acct.getKycStatus())) {
            throw new IllegalArgumentException("Only under-review accounts can be rejected (was " + acct.getKycStatus() + ")");
        }
        acct.reject(reviewerId, reason);
        return accounts.save(acct);
    }
}
