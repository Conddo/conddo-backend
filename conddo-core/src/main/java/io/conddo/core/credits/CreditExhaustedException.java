package io.conddo.core.credits;

import java.util.UUID;

/** Thrown by {@link CreditService} when the tenant has no credits left.
 *  Carries the action type + cost so the FE can render a helpful message. */
public class CreditExhaustedException extends RuntimeException {

    private final UUID tenantId;
    private final String actionType;
    private final int required;
    private final int available;

    public CreditExhaustedException(UUID tenantId, String actionType, int required, int available) {
        super("Tenant " + tenantId + " out of credits: needs " + required + ", has " + available
                + " (action=" + actionType + ")");
        this.tenantId = tenantId;
        this.actionType = actionType;
        this.required = required;
        this.available = available;
    }

    public UUID getTenantId() { return tenantId; }
    public String getActionType() { return actionType; }
    public int getRequired() { return required; }
    public int getAvailable() { return available; }
}
