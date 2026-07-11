package io.conddo.core.service;

import io.conddo.core.repository.TenantCreditAccountRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.TenantSiteRepository;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform snapshot for the admin dashboard on studio.getconddo.com. Reads
 * across every tenant, so runs under the {@link TenantScoped#crossTenant()}
 * aspect — the RLS carve-out is bound + auto-cleared for the transaction.
 *
 * <p>Deliberately small: QA queue depth, signup momentum, tier/vertical mix,
 * platform-wide credit burn. Enough to answer "is the business healthy today"
 * without a real BI layer.
 */
@Service
public class PlatformOverviewService {

    private final TenantRepository tenants;
    private final TenantSiteRepository tenantSites;
    private final TenantCreditAccountRepository creditAccounts;

    public PlatformOverviewService(TenantRepository tenants,
                                    TenantSiteRepository tenantSites,
                                    TenantCreditAccountRepository creditAccounts) {
        this.tenants = tenants;
        this.tenantSites = tenantSites;
        this.creditAccounts = creditAccounts;
    }

    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public Overview snapshot() {
        OffsetDateTime thirtyDaysAgo = OffsetDateTime.now().minusDays(30);

        long totalTenants = tenants.countAll();
        long newLast30Days = tenants.countCreatedSince(thirtyDaysAgo);
        long pendingQa = tenantSites.findByQaApprovedFalseOrderByCreatedAtDesc().size();
        long activeSites = tenantSites.findByActiveTrueOrderByCreatedAtDesc().size();
        long totalCreditsUsed = creditAccounts.totalCreditsUsedPlatformWide();

        Map<String, Long> byVertical = new LinkedHashMap<>();
        for (TenantRepository.VerticalCount row : tenants.countByVertical()) {
            byVertical.put(row.getVertical(), row.getCount());
        }

        Map<String, Long> byTier = new LinkedHashMap<>();
        for (TenantCreditAccountRepository.TierCount row : creditAccounts.countByTier()) {
            byTier.put(row.getTier(), row.getCount());
        }

        return new Overview(
                totalTenants, newLast30Days, pendingQa, activeSites,
                totalCreditsUsed, byVertical, byTier);
    }

    public record Overview(
            long totalTenants,
            long newTenantsLast30Days,
            long pendingQaCount,
            long activeSitesCount,
            long totalCreditsUsedPlatformWide,
            Map<String, Long> tenantsByVertical,
            Map<String, Long> tenantsByTier
    ) {}
}
