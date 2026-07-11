package io.conddo.core.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired when an order is created — either through the merchant dashboard
 * (source {@code DASHBOARD}) or through the public website surface
 * (source {@code PUBLIC_WEBSITE}). Consumers fan out from here: the merchant
 * notification listener fires email + SMS + bell-feed only on
 * {@code PUBLIC_WEBSITE} since the dashboard caller already knows about
 * orders they typed in themselves.
 *
 * <p>Only IDs and small primitives are carried — listeners load the
 * {@link io.conddo.core.domain.Order} themselves so they always see the
 * committed state. {@code totalNgn} is the integer-naira (NOT kobo)
 * value for convenience in human-facing messages.
 */
public record OrderCreatedEvent(
        UUID tenantId,
        UUID orderId,
        String orderReference,
        String customerName,
        BigDecimal totalNgn,
        Source source) implements DomainEvent {

    public enum Source {
        /** Manually-typed order from the merchant dashboard. */
        DASHBOARD,
        /** Order placed by a customer on the merchant's public website. */
        PUBLIC_WEBSITE
    }
}
