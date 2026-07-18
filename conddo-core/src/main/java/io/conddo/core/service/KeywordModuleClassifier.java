package io.conddo.core.service;

import io.conddo.core.registry.ModuleCatalogue;
import io.conddo.core.registry.VerticalDataLoader;
import io.conddo.core.registry.VerticalDefinition;
import io.conddo.core.registry.VerticalKeywordMatcher;
import io.conddo.core.service.ModuleSuggestionService.Result;
import io.conddo.core.service.ModuleSuggestionService.Score;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic, keyword-driven module classifier — the non-AI counterpart
 * to {@link ModuleSuggestionService}. Same {@code Result} shape, no LLM call,
 * no credit spend, no rate-limit ceiling.
 *
 * <p>Two signals combined:
 * <ul>
 *   <li><b>Vertical scan</b> — {@link VerticalKeywordMatcher} picks the
 *       best-scoring vertical from the description. The vertical's own
 *       {@code starterTools} become the primary recommended module set.</li>
 *   <li><b>Pain-signal scan</b> — every target tenant is an owner-led SME
 *       running customers / orders / inventory / payments on WhatsApp and
 *       spreadsheets. Words like "whatsapp", "spreadsheet", "excel",
 *       "ledger", "cash sales" bump {@code crm}, {@code orders},
 *       {@code inventory}, {@code payments}, and {@code whatsapp.orders}
 *       to high confidence regardless of vertical — they're what these
 *       businesses need to escape the manual grind.</li>
 * </ul>
 *
 * <p>When the description matches no keywords at all, the result falls back
 * to {@code general} vertical + the four cross-vertical baseline modules.
 */
@Service
public class KeywordModuleClassifier {

    private static final Logger log = LoggerFactory.getLogger(KeywordModuleClassifier.class);

    /** Cross-vertical baseline for target-persona SMEs. Every classification
     *  starts from this set; the vertical scan adds/promotes on top. */
    private static final List<String> BASELINE_MODULES = List.of(
            "crm", "orders", "inventory", "payments", "whatsapp.orders");

    /** Owner-led-SME pain signals. Hitting any of these means the tenant is
     *  in the "juggling whatsapp + excel" bucket — force the baseline set
     *  to high confidence and add {@code analytics} so they see the impact. */
    private static final List<Pattern> PAIN_SIGNALS = List.of(
            wholeWord("whatsapp"), wholeWord("spreadsheet"), wholeWord("spreadsheets"),
            wholeWord("excel"), wholeWord("google sheets"), wholeWord("sheets"),
            wholeWord("ledger"), wholeWord("book"), wholeWord("books"),
            wholeWord("cash sales"), wholeWord("cash book"), wholeWord("manual"),
            wholeWord("by hand"), wholeWord("notebook"), wholeWord("record book"),
            wholeWord("sole proprietor"), wholeWord("owner"), wholeWord("myself"),
            wholeWord("i run"), wholeWord("i manage"), wholeWord("small business"));

    private final VerticalKeywordMatcher keywordMatcher;
    private final VerticalDataLoader verticals;

    public KeywordModuleClassifier(VerticalKeywordMatcher keywordMatcher,
                                    VerticalDataLoader verticals) {
        this.keywordMatcher = keywordMatcher;
        this.verticals = verticals;
    }

    /** Same wire shape as {@link ModuleSuggestionService#suggest(String, String)},
     *  so callers can swap providers behind a flag. */
    public Result classify(String description, String verticalHint) {
        String desc = description == null ? "" : description;
        boolean hasDescription = !desc.isBlank();
        boolean hasHint = verticalHint != null && !verticalHint.isBlank()
                && verticals.all().containsKey(verticalHint.trim().toLowerCase(Locale.ROOT));
        if (!hasDescription && !hasHint) {
            throw new IllegalArgumentException(
                    "Either businessDescription or verticalHint is required");
        }
        String descLower = desc.toLowerCase(Locale.ROOT);

        // 1. Vertical resolution — hint wins if valid; otherwise best keyword match; otherwise "general".
        String vertical = resolveVertical(descLower, verticalHint);
        double verticalConfidence = verticalConfidenceOf(descLower, vertical);

        // 2. Pain signals — how strongly does this look like the target persona?
        int painHits = countPainSignals(descLower);
        boolean isTargetPersona = painHits > 0;

        // 3. Score every known module.
        VerticalDefinition def = verticals.all().get(vertical);
        Set<String> verticalStarter = def == null ? Set.of() : new java.util.LinkedHashSet<>(def.starterTools());
        Set<String> verticalBusiness = def == null ? Set.of() : new java.util.LinkedHashSet<>(def.businessToolsAdd());

        Set<String> allIds = new TreeSet<>();
        for (VerticalDefinition v : verticals.all().values()) {
            allIds.addAll(v.starterTools());
            allIds.addAll(v.businessToolsAdd());
            allIds.addAll(v.proToolsAdd());
        }
        // Ensure baseline modules always score even if not in any vertical file.
        allIds.addAll(BASELINE_MODULES);

        Map<String, Score> scored = new LinkedHashMap<>();
        for (String id : allIds) {
            double confidence;
            String reason;
            if (BASELINE_MODULES.contains(id) && isTargetPersona) {
                confidence = 0.90;
                reason = "Baseline for owner-led SMEs currently on WhatsApp + spreadsheets.";
            } else if (verticalStarter.contains(id)) {
                confidence = 0.85;
                reason = "Starter tool for the " + vertical + " vertical.";
            } else if (BASELINE_MODULES.contains(id)) {
                confidence = 0.70;
                reason = "Common SME baseline module.";
            } else if (verticalBusiness.contains(id)) {
                confidence = 0.55;
                reason = "Business-tier add-on for the " + vertical + " vertical.";
            } else {
                confidence = 0.20;
                reason = "Not in this vertical's default preset.";
            }
            scored.put(id, new Score(id, confidence, reason));
        }

        List<Score> sorted = new ArrayList<>(scored.values());
        sorted.sort(Comparator.comparingDouble(Score::confidence).reversed());
        log.debug("Keyword classify: vertical={} conf={} painHits={} description=[{}]",
                vertical, verticalConfidence, painHits, description);
        return new Result(sorted, vertical, verticalConfidence);
    }

    // ----- helpers ---------------------------------------------------------

    private String resolveVertical(String descLower, String hint) {
        if (hint != null && !hint.isBlank()) {
            String h = hint.trim().toLowerCase(Locale.ROOT);
            if (verticals.all().containsKey(h)) {
                return h;
            }
        }
        List<VerticalKeywordMatcher.Match> matches = keywordMatcher.topMatches(descLower);
        if (matches.isEmpty()) {
            return "general";
        }
        // Retail is a weak fallback — if it wins by a hair, prefer general so
        // professional-service / knowledge-work tenants don't leak into a
        // retail sidebar. Retail only wins when it beats runner-up by 2+.
        VerticalKeywordMatcher.Match top = matches.get(0);
        if ("retail".equals(top.verticalId()) && matches.size() > 1
                && top.score() - matches.get(1).score() < 2) {
            return matches.get(1).verticalId();
        }
        return top.verticalId();
    }

    /** Rough confidence for the vertical pick — top keyword score capped
     *  and normalised. Perfect for the FE meter without pretending precision. */
    private double verticalConfidenceOf(String descLower, String vertical) {
        List<VerticalKeywordMatcher.Match> matches = keywordMatcher.topMatches(descLower);
        for (VerticalKeywordMatcher.Match m : matches) {
            if (m.verticalId().equals(vertical)) {
                // score 1 → 0.4, score 3 → 0.7, score 6+ → 0.95.
                return Math.min(0.95, 0.35 + (m.score() * 0.1));
            }
        }
        return 0.35;
    }

    private static int countPainSignals(String descLower) {
        int hits = 0;
        for (Pattern p : PAIN_SIGNALS) {
            if (p.matcher(descLower).find()) {
                hits++;
            }
        }
        return hits;
    }

    private static Pattern wholeWord(String phrase) {
        return Pattern.compile("\\b" + Pattern.quote(phrase.toLowerCase(Locale.ROOT)) + "\\b",
                Pattern.CASE_INSENSITIVE);
    }

    /** Exposed so a test / FE preview can print each module's assigned
     *  role without re-scoring. */
    public static String describe(String moduleId) {
        return ModuleCatalogue.describe(moduleId);
    }
}
