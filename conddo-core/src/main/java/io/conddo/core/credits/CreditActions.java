package io.conddo.core.credits;

import java.util.Map;

/**
 * Canonical action type strings + their credit cost.
 *
 * <p><b>The principle (Billing Spec):</b> free actions build habit, paid
 * actions deliver value. Users should never feel punished for just looking
 * at their data. Manual CRUD (add customer, log order) is always free —
 * only AI-assisted variants and outbound money-making actions consume
 * credits.
 *
 * <p><b>Tiering:</b>
 * <ul>
 *   <li><b>HIGH</b> (5-10 credits) — outbound work that reaches customers
 *       and/or requires significant AI + delivery infra: marketing blasts,
 *       AI campaigns, insight reports, website generation.</li>
 *   <li><b>MEDIUM</b> (2-5 credits) — AI-assisted operational actions where
 *       the AI does real work but the outcome is internal: prefilling a
 *       customer record, writing a social post, generating a graphic.</li>
 *   <li><b>Not listed here at all</b> — manual CRUD, dashboards, analytics,
 *       navigation. These NEVER call {@link CreditService}. Unknown action
 *       lookup throws so a typo can't silently bypass metering, but a
 *       "customer added manually" simply doesn't touch this class.</li>
 * </ul>
 *
 * <p>Adding a new AI-consuming feature? Register the action here first with
 * its cost, then have the calling service pass the constant to
 * {@code CreditService.consume/reserve}. New actions without a cost entry
 * fail loudly.
 */
public final class CreditActions {

    // ===========================================================================
    // HIGH — money-making outbound work.
    // ===========================================================================

    /** Signup-time AI provisioning (business classification + module scoring).
     *  Booked one-time at tenant creation via {@link CreditService#provisionAccount}. */
    public static final String AI_PROVISIONING = "ai.provisioning";

    /** Bulk SMS + email blast to a customer segment. Delivery infra + templating. */
    public static final String MARKETING_CUSTOMER_BLAST = "marketing.customer_blast";

    /** Multi-step AI-orchestrated marketing campaign (segment → generate → schedule → send). */
    public static final String MARKETING_AI_CAMPAIGN = "marketing.ai_campaign";

    /** AI reads the tenant's whole dataset and generates a business insight report. */
    public static final String INSIGHT_BUSINESS_REPORT = "insight.business_report";

    /** AI-driven follow-up sequence for an unpaid invoice (SMS/email over N days). */
    public static final String PAYMENT_FOLLOWUP_SEQUENCE = "payment.followup_sequence";

    /** Initial website generation from the tenant's business description + vibe. */
    public static final String WEBSITE_GENERATION = "website.generation";

    /** Publish a website to the live subdomain (or custom domain) after review. */
    public static final String WEBSITE_PUBLISH = "website.publish";

    /** AI-authored outreach automation run (message per contact, N contacts). */
    public static final String OUTREACH_AI_AUTOMATION = "outreach.ai_automation";

    // ===========================================================================
    // MEDIUM — AI-assisted operational actions.
    // ===========================================================================

    /** AI-assisted customer creation (parse ID card / receipt to prefill).
     *  <b>Manual add customer is free</b> and does not call the credit service. */
    public static final String CUSTOMER_AI_ADD = "customer.ai_add";

    /** AI-assisted order logging (parse a receipt or voice note into an order).
     *  <b>Manual log order is free</b> and does not call the credit service. */
    public static final String ORDER_AI_LOG = "order.ai_log";

    /** AI writes + schedules a social media post.
     *  <b>Manual scheduling is free.</b> */
    public static final String SOCIAL_AI_SCHEDULE = "social.ai_schedule";

    /** AI-generated graphic asset (Gemini image generation via OpenRouter). */
    public static final String DESIGN_REQUEST = "design.request";

    /** AI regenerates the copy for one website section (about, hero, etc.). */
    public static final String AI_COPY_REGENERATION = "website.ai_copy_regeneration";

    // ===========================================================================
    // Cost table (Billing Spec — matrix v2, 2026-07-04).
    // ===========================================================================

    public static final Map<String, Integer> COSTS = Map.ofEntries(
            // HIGH tier
            Map.entry(AI_PROVISIONING,           10),
            Map.entry(MARKETING_CUSTOMER_BLAST,   8),
            Map.entry(MARKETING_AI_CAMPAIGN,     10),
            Map.entry(INSIGHT_BUSINESS_REPORT,    6),
            Map.entry(PAYMENT_FOLLOWUP_SEQUENCE,  5),
            Map.entry(WEBSITE_GENERATION,        10),
            Map.entry(WEBSITE_PUBLISH,            3),
            Map.entry(OUTREACH_AI_AUTOMATION,     6),
            // MEDIUM tier
            Map.entry(CUSTOMER_AI_ADD,            2),
            Map.entry(ORDER_AI_LOG,               2),
            Map.entry(SOCIAL_AI_SCHEDULE,         3),
            Map.entry(DESIGN_REQUEST,             5),
            Map.entry(AI_COPY_REGENERATION,       2)
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
