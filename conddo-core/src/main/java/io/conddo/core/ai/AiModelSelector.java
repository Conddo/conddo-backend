package io.conddo.core.ai;

import io.conddo.core.credits.CreditActions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Per-action model routing for the AI gateway. Different jobs want different
 * models:
 * <ul>
 *   <li>Classifier (high volume, structured JSON) → cheap fast model (DeepSeek V3)</li>
 *   <li>Website generation (rare, quality-critical) → premium model (Sonnet)</li>
 *   <li>Marketing copy (moderate volume, prose quality) → mid-tier (Gemini Flash)</li>
 *   <li>Daily Brief (once/day, personal) → cheap fast model</li>
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

        this.byAction = Map.of(
                // Onboarding classify + tenant reclassify — same action.
                CreditActions.AI_PROVISIONING, classifierModel,

                // Website generation + copy regeneration — quality-critical.
                CreditActions.WEBSITE_GENERATION, websiteModel,
                CreditActions.AI_COPY_REGENERATION, websiteModel,

                // Marketing (SMS / email / social copy) — prose matters.
                CreditActions.AI_MARKETING_MESSAGE, marketingModel,

                // Daily brief — once a day, keep costs down.
                "daily.brief", briefModel
        );
    }

    /** Returns the configured model for {@code actionType}, or {@code null} to
     *  let the adapter use its own default (from {@code CONDDO_OPENROUTER_MODEL}). */
    public String modelFor(String actionType) {
        if (actionType == null) return null;
        String override = byAction.get(actionType);
        return (override == null || override.isBlank()) ? null : override;
    }
}
