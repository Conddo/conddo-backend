package io.conddo.core.service;

import io.conddo.core.domain.CommissionEntry;
import io.conddo.core.domain.Deal;
import io.conddo.core.repository.CommissionEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Accrual + payout of real estate deal commissions.
 *
 * <p><b>Nigerian cadence:</b> commission accrues when the deal reaches
 * {@code deposit_paid} — not at signature, not at close. If a deal has
 * both a primary agent and an introducer, the total is split 60/40 by
 * default (configurable per deal by the tenant when they add the introducer).
 */
@Service
public class CommissionService {

    /** Default split when a deal has both a primary agent and an introducer.
     *  Tenant can override per-deal in a future editor. */
    public static final BigDecimal DEFAULT_PRIMARY_SHARE = BigDecimal.valueOf(60);
    public static final BigDecimal DEFAULT_INTRODUCER_SHARE = BigDecimal.valueOf(40);

    private static final Logger log = LoggerFactory.getLogger(CommissionService.class);

    private final CommissionEntryRepository repository;

    public CommissionService(CommissionEntryRepository repository) {
        this.repository = repository;
    }

    /** Accrue commission entries for a deal that just hit {@code deposit_paid}.
     *  Idempotent — returns existing entries if this deal already has
     *  accrued rows; the FE can call {@code #reverseAndRegenerate} to change
     *  the split after the fact. */
    @Transactional
    public List<CommissionEntry> accrueForDeposit(Deal deal) {
        if (deal.getCommissionAmount() == null
                || deal.getCommissionAmount().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Deal {} has no commission_amount; skipping accrual", deal.getId());
            return List.of();
        }
        if (deal.getPrimaryAgentId() == null) {
            log.info("Deal {} has no primary agent; skipping accrual", deal.getId());
            return List.of();
        }
        List<CommissionEntry> existing = repository.findByDealId(deal.getId());
        if (!existing.isEmpty()) {
            return existing;
        }

        BigDecimal total = deal.getCommissionAmount();
        if (deal.getIntroducerAgentId() != null) {
            BigDecimal primaryAmount = total
                    .multiply(DEFAULT_PRIMARY_SHARE)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal introducerAmount = total.subtract(primaryAmount);
            return List.of(
                    repository.save(new CommissionEntry(
                            deal.getTenantId(), deal.getId(), deal.getPrimaryAgentId(),
                            CommissionEntry.ROLE_PRIMARY, DEFAULT_PRIMARY_SHARE, primaryAmount)),
                    repository.save(new CommissionEntry(
                            deal.getTenantId(), deal.getId(), deal.getIntroducerAgentId(),
                            CommissionEntry.ROLE_INTRODUCER, DEFAULT_INTRODUCER_SHARE, introducerAmount))
            );
        }
        return List.of(repository.save(new CommissionEntry(
                deal.getTenantId(), deal.getId(), deal.getPrimaryAgentId(),
                CommissionEntry.ROLE_PRIMARY, BigDecimal.valueOf(100), total)));
    }
}
