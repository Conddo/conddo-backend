package io.conddo.core.credits;

import java.util.Map;

/**
 * Canonical action type strings + their credit cost, per the Billing spec.
 * New modules add their own entries here rather than sprinkling magic
 * numbers across the code — the CreditService looks up cost by action.
 *
 * <p>Never-gated actions (customer records, payments, dashboard views)
 * are absent by design; a lookup for a missing action fails loudly rather
 * than silently costing 0.
 */
public final class CreditActions {

    // AI provisioning at signup — 10 credits, one-time. The signup flow
    // reserves before firing OpenRouter and confirms on success.
    public static final String AI_PROVISIONING = "ai.provisioning";

    // Every processed order — sync consume.
    public static final String ORDER_PROCESSED = "order.processed";

    // Every automation / workflow trigger fired.
    public static final String WORKFLOW_TRIGGER = "workflow.trigger";

    // Every AI-generated marketing message sent (email / SMS body).
    public static final String AI_MARKETING_MESSAGE = "marketing.ai_message";

    // Website generation / regeneration (heavy AI + template render).
    public static final String WEBSITE_GENERATION = "website.generation";

    // AI copy regeneration per website section (about, hero, etc.).
    public static final String AI_COPY_REGENERATION = "website.ai_copy_regeneration";

    public static final Map<String, Integer> COSTS = Map.of(
            AI_PROVISIONING,        10,
            ORDER_PROCESSED,         1,
            WORKFLOW_TRIGGER,        2,
            AI_MARKETING_MESSAGE,    3,
            WEBSITE_GENERATION,      5,
            AI_COPY_REGENERATION,    2
    );

    /** Look up the credit cost for an action. Throws when unknown so a
     *  typo or a new module adding an unregistered action doesn't
     *  silently bypass metering. */
    public static int costOf(String actionType) {
        Integer cost = COSTS.get(actionType);
        if (cost == null) {
            throw new IllegalArgumentException("Unknown credit action: " + actionType);
        }
        return cost;
    }

    private CreditActions() {
    }
}
