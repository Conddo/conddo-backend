package io.conddo.api.pharmacy;

import io.conddo.core.events.OrderStageChangedEvent;
import io.conddo.core.service.PharmacyLoyaltyService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pharmacy Roadmap Beta 1 — credits a customer's cashback wallet when
 * an order's stage transitions to {@code DELIVERED}. Spec is explicit:
 * never on placement, only after the customer has the product. Fires
 * on the merchant-side {@code OrderService.transition} path; the
 * public-website checkout flow doesn't carry orders into DELIVERED
 * directly so this is only the dashboard pipeline today.
 */
@Component
public class CashbackCreditListener {

    private static final Logger log = LoggerFactory.getLogger(CashbackCreditListener.class);

    private final PharmacyLoyaltyService loyaltyService;
    private final TenantSession tenantSession;

    public CashbackCreditListener(PharmacyLoyaltyService loyaltyService,
                                  TenantSession tenantSession) {
        this.loyaltyService = loyaltyService;
        this.tenantSession = tenantSession;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderStageChanged(OrderStageChangedEvent event) {
        if (event.toStage() == null) {
            return;
        }
        // Pharmacy uses "Delivered" today; tolerate any case variation
        // so a tenant who customised their stage name still gets the
        // credit. Other terminal stages (Completed, Done, Closed) are
        // NOT cashback-eligible per spec — the customer has to have
        // taken delivery.
        if (!"delivered".equalsIgnoreCase(event.toStage().trim())) {
            return;
        }
        try {
            TenantContext.set(event.tenantId());
            tenantSession.bind();
            loyaltyService.creditCashback(event.customerId(), event.orderId(), event.totalNgn());
        } catch (RuntimeException ex) {
            log.warn("Cashback credit failed for tenant {} order {}: {}",
                    event.tenantId(), event.orderId(), ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
