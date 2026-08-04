package io.conddo.core.service;

import io.conddo.core.domain.PaymentIntent;
import io.conddo.core.payments.PaymentProviders;
import io.conddo.core.repository.PaymentIntentRepository;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Cross-tenant PaymentIntent reads + re-verify — powers /admin/payments.
 *
 * <p>All methods run under {@code @TenantScoped(crossTenant = true)} so
 * RLS lets them see every tenant's intents (support triage needs to
 * inspect any intent regardless of tenant).
 */
@Service
public class AdminPaymentsService {

    private final PaymentIntentRepository intents;
    private final PaymentProviders providers;

    public AdminPaymentsService(PaymentIntentRepository intents, PaymentProviders providers) {
        this.intents = intents;
        this.providers = providers;
    }

    /** Platform-wide intent listing, most recent first. Optional status filter. */
    @Transactional(readOnly = true)
    @TenantScoped(crossTenant = true)
    public Page<PaymentIntent> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return intents.findAllByOrderByInitiatedAtDesc(pageable);
        }
        return intents.findByStatusOrderByInitiatedAtDesc(status, pageable);
    }

    /** Platform summary — gross succeeded volume + counts by status. */
    @Transactional(readOnly = true)
    @TenantScoped(crossTenant = true)
    public PlatformSummary summary() {
        long grossSucceededKobo = intents.sumAmountKoboByStatusAll(PaymentIntent.STATUS_SUCCEEDED);
        long refundedKobo = intents.sumAmountKoboByStatusAll(PaymentIntent.STATUS_REFUNDED);
        return new PlatformSummary(
                grossSucceededKobo,
                refundedKobo,
                intents.countByStatus(PaymentIntent.STATUS_SUCCEEDED),
                intents.countByStatus(PaymentIntent.STATUS_PENDING),
                intents.countByStatus(PaymentIntent.STATUS_FAILED),
                intents.countByStatus(PaymentIntent.STATUS_REFUNDED)
        );
    }

    /** Single intent detail — for the admin drill-down. */
    @Transactional(readOnly = true)
    @TenantScoped(crossTenant = true)
    public PaymentIntent get(UUID intentId) {
        return intents.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("No payment intent " + intentId));
    }

    /**
     * Force a re-verify against the provider. Support use — a stuck
     * intent gets a manual nudge to reconcile.
     */
    @Transactional
    @TenantScoped(crossTenant = true)
    public PaymentIntent reverify(UUID intentId) {
        PaymentIntent intent = get(intentId);
        PaymentIntent verified = providers.require(intent.getProvider()).verifyCharge(intent);
        return intents.save(verified);
    }

    public record PlatformSummary(
            long grossSucceededKobo,
            long refundedKobo,
            long succeededCount,
            long pendingCount,
            long failedCount,
            long refundedCount
    ) {}
}
