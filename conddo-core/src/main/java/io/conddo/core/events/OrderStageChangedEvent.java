package io.conddo.core.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published when an order's stage changes. Listeners can opt into
 * specific transitions (e.g. the cashback listener fires only on
 * → DELIVERED). Carrying customer + total here means the listener
 * doesn't need to reload the order.
 */
public record OrderStageChangedEvent(UUID tenantId,
                                     UUID orderId,
                                     UUID customerId,
                                     String fromStage,
                                     String toStage,
                                     BigDecimal totalNgn) implements DomainEvent {
}
