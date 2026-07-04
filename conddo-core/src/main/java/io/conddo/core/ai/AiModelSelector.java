package io.conddo.core.ai;

import io.conddo.core.credits.CreditActions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-action model routing for the AI gateway. Different jobs want different
 * models:
 * <ul>
 *   <li>Classifier + provisioning (structured JSON, high volume) → cheap fast model (DeepSeek V3)</li>
 *   <li>Website generation + publish (quality-critical) → premium model (Sonnet)</li>
 *   <li>Marketing + campaign copy (prose quality) → mid-tier (Gemini 2.5 Flash)</li>
 *   <li>Insight reports (long context reasoning) → premium model (Sonnet)</li>
 *   <li>Daily brief (once/day, personal) → cheap fast model</li>
 * </ul>
 *
 * <p>Overrides come from env vars so the choice is a config change, not a
 * code change:
 * <pre>
 *   CONDDO_OPENROUTER_MODEL_CLASSIFIER=deepseek/deepseek-chat
 *   CONDDO_OPENROUTER_MODEL_WEBSITE=anthropic/claude-sonnet-4-5
 *   CONDDO_OPENROUTER_MODEL_MARKETING=google/gemini-2.5-flash
 *   CONDDO_OPENROUTER_MODEL_BRIEF=deepseek/deepseek-chat
 * </pre>
 *
 * <p>Any action without an override falls back to
 * {@code CONDDO_OPENROUTER_MODEL} — the global default. Unknown actions also
 * hit the default so a new action type doesn't crash mid-call.
 */
@Component
public class AiModelSelector {

    private final Map<String, String> byAction;

    public AiModelSelector(
            @Value("${conddo.openrouter.model-classifier:}") String classifierModel,
            @Value("${conddo.openrouter.model-website:}") String websiteModel,
            @Value("${conddo.openrouter.model-marketing:}") String marketingModel,
            @Value("${conddo.openrouter.model-brief:}") String briefModel) {

        Map<String, String> map = new HashMap<>();

        // Classifier bucket — DeepSeek is more than enough; low latency + cheap.
        map.put(CreditActions.AI_PROVISIONING, classifierModel);

        // Website bucket — quality matters; a bad hero headline kills trust.
        // Sonnet by default; user can flip to Gemini 2.5 Pro or Nemotron via env var.
        map.put(CreditActions.WEBSITE_GENERATION, websiteModel);
        map.put(CreditActions.WEBSITE_PUBLISH, websiteModel);
        map.put(CreditActions.AI_COPY_REGENERATION, websiteModel);

        // Marketing + outbound bucket — prose quality matters; volume is moderate.
        // Gemini 2.5 Flash is the sweet spot: cheap, good creative writing.
        map.put(CreditActions.MARKETING_CUSTOMER_BLAST, marketingModel);
        map.put(CreditActions.MARKETING_AI_CAMPAIGN, marketingModel);
        map.put(CreditActions.PAYMENT_FOLLOWUP_SEQUENCE, marketingModel);
        map.put(CreditActions.OUTREACH_AI_AUTOMATION, marketingModel);
        map.put(CreditActions.SOCIAL_AI_SCHEDULE, marketingModel);

        // Insight bucket — needs long context reasoning; ride the website slot
        // (Sonnet) rather than adding a fifth env var.
        map.put(CreditActions.INSIGHT_BUSINESS_REPORT, websiteModel);

        // MEDIUM AI-assisted ops — cheap classifier is fine (structured parsing).
        map.put(CreditActions.CUSTOMER_AI_ADD, classifierModel);
        map.put(CreditActions.ORDER_AI_LOG, classifierModel);

        // Design requests go through a different pipeline (Gemini image gen),
        // not this text gateway — no override registered.

        // Daily brief keeps its own action-key string so callers don't have
        // to reason about which CreditAction is closest.
        map.put("daily.brief", briefModel);

        this.byAction = Map.copyOf(map);
    }

    /** Returns the configured model for {@code actionType}, or {@code null} to
     *  let the adapter use its own default (from {@code CONDDO_OPENROUTER_MODEL}). */
    public String modelFor(String actionType) {
        if (actionType == null) return null;
        String override = byAction.get(actionType);
        return (override == null || override.isBlank()) ? null : override;
    }
}
