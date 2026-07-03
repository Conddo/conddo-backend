package io.conddo.core.ai;

import java.util.UUID;

/**
 * Everything the AI gateway needs to route an outbound LLM call: the tenant
 * paying for it, the caller (for the verified-email gate), what the call is
 * for (drives credit cost + audit categorization), and the domain object the
 * result is tied to (for the "why did I lose these credits?" answer on the
 * dashboard breakdown).
 *
 * <p>A {@code null} tenantId means the caller is pre-tenant (the onboarding
 * classifier). Those calls bypass the credit + verified-email checks — the
 * one-time AI provisioning charge is booked separately at tenant creation.
 * All other AI calls MUST supply tenantId.
 */
public record AiCallContext(
        UUID tenantId,
        UUID userId,
        String actionType,
        UUID referenceId,
        String referenceType
) {

    /** Convenience for the pre-tenant onboarding classifier — bypasses
     *  credit + verified-email checks; the gateway just proxies to OpenRouter. */
    public static AiCallContext preTenant(String actionType) {
        return new AiCallContext(null, null, actionType, null, null);
    }

    /** Tenant-scoped call without a specific domain reference. */
    public static AiCallContext forTenant(UUID tenantId, UUID userId, String actionType) {
        return new AiCallContext(tenantId, userId, actionType, null, null);
    }
}
