package io.conddo.core.registry;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the capability-tool set a tenant gets for its
 * {@code vertical × plan} (Architecture v1.0 §5, the matrix in
 * {@code VERTICALS.md}). Plan tiers are cumulative: business
 * includes all of starter, pro all of business. Backs the JWT
 * {@code activeModules} claim (§4.4) and the manifest endpoint
 * (§16).
 *
 * <p>Data is loaded from {@code classpath:verticals/*.yml} by
 * {@link VerticalDataLoader}. Adding a vertical = dropping a YAML
 * file; no code edit needed.
 */
@Component
public class VerticalToolMatrix {

    /** Used when a tenant's vertical isn't one of the canonical ones (e.g. "general"). */
    public static final String DEFAULT_VERTICAL = "default";

    private static final List<String> PLAN_ORDER = List.of("starter", "business", "pro");

    /** vertical → (tier → cumulative tool list). */
    private final Map<String, Map<String, List<String>>> matrix;

    public VerticalToolMatrix(VerticalDataLoader loader) {
        Map<String, Map<String, List<String>>> built = new LinkedHashMap<>();
        for (Map.Entry<String, VerticalDefinition> e : loader.all().entrySet()) {
            built.put(e.getKey(), tiers(
                    e.getValue().starterTools(),
                    e.getValue().businessToolsAdd(),
                    e.getValue().proToolsAdd()));
        }
        this.matrix = Map.copyOf(built);
    }

    /** The active tool ids for a tenant's vertical + plan (never null). */
    public List<String> resolve(String vertical, String plan) {
        Map<String, List<String>> byTier = matrix.getOrDefault(
                normalizeVertical(vertical), matrix.get(DEFAULT_VERTICAL));
        return byTier.getOrDefault(normalizePlan(plan), byTier.get("starter"));
    }

    /**
     * Normalise a stored plan to a known tier. The matrix keys are the
     * INTERNAL tier axis ({@code starter/business/pro}) — pricing tiers
     * ({@code free/student/starter/growth/pro} per V67) translate down onto
     * it here:
     * <ul>
     *   <li>{@code free / student / starter} → {@code starter}
     *       (free + student mirror starter functionally; credit budgets differ)</li>
     *   <li>{@code growth} → {@code business}</li>
     *   <li>{@code pro} → {@code pro}</li>
     * </ul>
     * Legacy names ({@code launcher / scaler}) are still accepted as an alias
     * so a JWT minted before V67 keeps working until it expires. Unknown
     * inputs fall back to {@code starter}.
     */
    public String normalizePlan(String plan) {
        String p = plan == null ? "" : plan.trim().toLowerCase();
        String mapped = switch (p) {
            case "free", "student", "starter" -> "starter";
            case "growth"                     -> "business";
            case "pro"                        -> "pro";
            // Legacy aliases from pre-V67 JWTs:
            case "launcher"                   -> "starter";
            case "scaler"                     -> "pro";
            default                           -> p;
        };
        return PLAN_ORDER.contains(mapped) ? mapped : "starter";
    }

    private String normalizeVertical(String vertical) {
        String v = vertical == null ? "" : vertical.trim().toLowerCase();
        return matrix.containsKey(v) ? v : DEFAULT_VERTICAL;
    }

    /** Builds the cumulative starter/business/pro lists from each tier's additions. */
    private static Map<String, List<String>> tiers(List<String> starter, List<String> businessAdds,
                                                    List<String> proAdds) {
        List<String> business = cumulative(starter, businessAdds);
        List<String> pro = cumulative(business, proAdds);
        return Map.of("starter", List.copyOf(starter), "business", business, "pro", pro);
    }

    private static List<String> cumulative(List<String> base, List<String> adds) {
        Set<String> all = new LinkedHashSet<>(base);
        all.addAll(adds);
        return List.copyOf(all);
    }
}
