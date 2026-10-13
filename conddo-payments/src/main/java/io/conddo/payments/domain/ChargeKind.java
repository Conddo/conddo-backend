package io.conddo.payments.domain;

/**
 * What the payment is for — drives the RoutePay routing decision.
 * Merchant-customer charges ({@link #BOOKING_DEPOSIT}, {@link #ORDER}) fan
 * out to the tenant's RoutePay sub-account (the merchant gets paid).
 *
 * <p>Mirrors the {@code charge_kind} CHECK constraint.
 */
public enum ChargeKind {

    BOOKING_DEPOSIT(true),
    ORDER(true);

    /** True when the charge should route to the tenant's sub-account. */
    private final boolean routesToTenantSubaccount;

    ChargeKind(boolean routesToTenantSubaccount) {
        this.routesToTenantSubaccount = routesToTenantSubaccount;
    }

    public boolean routesToTenantSubaccount() {
        return routesToTenantSubaccount;
    }
}
