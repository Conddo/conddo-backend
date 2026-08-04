package io.conddo.core.service;

import io.conddo.core.domain.Payout;
import io.conddo.core.repository.PayoutRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing payouts read. Powers /payments/payouts (Phase 4).
 *
 * <p>Payouts are populated by the provider's payout webhook — Importapay
 * hasn't documented one publicly yet, so this surface renders empty
 * for real tenants today. It's shipped as a shell so:
 * <ul>
 *   <li>Tenants can see the concept + expected UX (upcoming settlement,
 *       history, bank destination snapshot).</li>
 *   <li>Once Importapay's payout stream is wired (or we add a poll-based
 *       sync), no FE change is needed — payouts just start showing up.</li>
 * </ul>
 */
@Service
public class PayoutService {

    private final PayoutRepository payouts;

    public PayoutService(PayoutRepository payouts) {
        this.payouts = payouts;
    }

    @Transactional(readOnly = true)
    @TenantScoped
    public List<Payout> listForTenant() {
        UUID tenantId = TenantContext.require();
        return payouts.findByTenantIdOrderByInitiatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    @TenantScoped
    public Payout get(UUID id) {
        return payouts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payout not found: " + id));
    }
}
