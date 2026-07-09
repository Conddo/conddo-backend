package io.conddo.core.registry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fast, deterministic vertical-scoring pre-pass fed to the LLM classifier.
 *
 * <p>The LLM alone drifts on ambiguous descriptions ("I sell things to
 * customers"). Adding a keyword scan as a prior — "the words 'ankara' and
 * 'tailor' appeared" — cuts miss rate a lot without adding real cost or
 * latency: the scan is a handful of regex checks against the raw description.
 *
 * <p>The prompt receives the top 3 matches. The LLM still makes the final
 * call, so genuinely-off keywords (a marketing consultancy that used the
 * word "shop" once) don't force a wrong vertical.
 *
 * <p><b>Keyword weights</b> — rare, unambiguous terms ("ankara", "paracetamol")
 * count more than common ones ("shop", "sell"). Compound terms
 * ("business card", "hair salon") are scored higher than single words.
 *
 * <p>Add a keyword to the wrong bucket? File a PR. This lives outside the
 * LLM prompt so the fix ships fast.
 */
@Component
public final class VerticalKeywordMatcher {

    private static final int TOP_K = 3;

    /** {@code Pattern} matches whole words case-insensitively. Compiled once. */
    private record Term(Pattern pattern, int weight) {
        static Term of(String phrase, int weight) {
            String escaped = Pattern.quote(phrase.toLowerCase(Locale.ROOT));
            Pattern p = Pattern.compile("\\b" + escaped + "\\b", Pattern.CASE_INSENSITIVE);
            return new Term(p, weight);
        }
    }

    /** Keyword catalog — the ONE place to add or refine vertical signals.
     *  Ordered so the first tests (rarest terms) fire first; scoring uses all. */
    private static final Map<String, List<Term>> KEYWORDS = buildCatalog();

    private static Map<String, List<Term>> buildCatalog() {
        Map<String, List<Term>> map = new LinkedHashMap<>();

        // ---- Pharmacy ------------------------------------------------------
        // Chemists, drug stores, community pharmacies. Uses very specific
        // medical vocabulary — high-signal keywords score 3.
        map.put("pharmacy", List.of(
                Term.of("pharmacy", 3), Term.of("chemist", 3), Term.of("drugstore", 3),
                Term.of("prescription", 3), Term.of("medication", 3), Term.of("medicine", 2),
                Term.of("paracetamol", 3), Term.of("otc", 2), Term.of("tablet", 1),
                Term.of("capsule", 1), Term.of("healthcare", 2), Term.of("patient", 2),
                Term.of("clinic", 2), Term.of("dispenser", 3), Term.of("pcn", 3),
                Term.of("nafdac", 2)
        ));

        // ---- Fashion -------------------------------------------------------
        // Tailoring shops, boutiques, ready-to-wear, custom clothing.
        // Nigerian-specific terms weighted heavily.
        map.put("fashion", List.of(
                Term.of("tailor", 3), Term.of("tailoring", 3), Term.of("boutique", 3),
                Term.of("ankara", 3), Term.of("adire", 3), Term.of("aso oke", 3),
                Term.of("aso ebi", 3), Term.of("agbada", 3), Term.of("buba", 3),
                Term.of("kaftan", 2), Term.of("dashiki", 2), Term.of("gele", 3),
                Term.of("clothing", 2), Term.of("apparel", 2), Term.of("fabric", 2),
                Term.of("fashion", 2), Term.of("dressmaker", 3), Term.of("seamstress", 3),
                Term.of("ready-to-wear", 3), Term.of("bespoke", 2)
        ));

        // ---- Food & Beverage -----------------------------------------------
        // Restaurants, bukas, catering, bars, cafes.
        map.put("food-and-beverage", List.of(
                Term.of("restaurant", 3), Term.of("eatery", 3), Term.of("buka", 3),
                Term.of("catering", 3), Term.of("caterer", 3), Term.of("cafe", 3),
                Term.of("cafeteria", 3), Term.of("bar", 2), Term.of("kitchen", 2),
                Term.of("jollof", 3), Term.of("suya", 3), Term.of("egusi", 3),
                Term.of("amala", 3), Term.of("pounded yam", 3), Term.of("shawarma", 2),
                Term.of("cook", 1), Term.of("chef", 2), Term.of("beverage", 2),
                Term.of("food", 1), Term.of("menu", 2), Term.of("meal", 1)
        ));

        // ---- Beauty & Wellness ---------------------------------------------
        // Salons, spas, barbers, cosmetics.
        map.put("beauty-and-wellness", List.of(
                Term.of("salon", 3), Term.of("spa", 3), Term.of("barber", 3),
                Term.of("barbing", 3), Term.of("hairdresser", 3), Term.of("stylist", 2),
                Term.of("makeup", 3), Term.of("mua", 3), Term.of("cosmetic", 2),
                Term.of("skincare", 3), Term.of("nails", 3), Term.of("pedicure", 3),
                Term.of("manicure", 3), Term.of("massage", 3), Term.of("wellness", 2),
                Term.of("lashes", 3), Term.of("wig", 3), Term.of("braids", 3),
                Term.of("locs", 2), Term.of("dreads", 2)
        ));

        // ---- Music Studio --------------------------------------------------
        // Recording studios, session musicians, producers, DJs, mixing services.
        map.put("music-studio", List.of(
                Term.of("studio", 2), Term.of("recording", 3), Term.of("mixing", 3),
                Term.of("mastering", 3), Term.of("producer", 3), Term.of("beat", 2),
                Term.of("session", 2), Term.of("music", 2), Term.of("dj", 2),
                Term.of("afrobeats", 3), Term.of("engineer", 1), Term.of("microphone", 3),
                Term.of("vocals", 2), Term.of("track", 1)
        ));

        // ---- Logistics -----------------------------------------------------
        map.put("logistics", List.of(
                Term.of("logistics", 3), Term.of("delivery", 3), Term.of("courier", 3),
                Term.of("dispatch", 3), Term.of("rider", 3), Term.of("bike", 1),
                Term.of("cargo", 3), Term.of("freight", 3), Term.of("shipping", 2),
                Term.of("haulage", 3), Term.of("transport", 2), Term.of("fleet", 2),
                Term.of("parcel", 3), Term.of("last-mile", 3)
        ));

        // ---- Professional Services -----------------------------------------
        // Consultancies, agencies, coaches, legal, accounting, real estate.
        map.put("professional-services", List.of(
                Term.of("consultancy", 3), Term.of("consulting", 3), Term.of("consultant", 3),
                Term.of("advisor", 2), Term.of("advisory", 3), Term.of("agency", 2),
                Term.of("coaching", 3), Term.of("coach", 2), Term.of("legal", 3),
                Term.of("law firm", 3), Term.of("lawyer", 3), Term.of("attorney", 2),
                Term.of("accounting", 3), Term.of("accountant", 3), Term.of("bookkeeping", 3),
                Term.of("marketing agency", 3), Term.of("digital agency", 3),
                Term.of("real estate", 3), Term.of("realtor", 3),
                Term.of("architect", 3), Term.of("freelance", 2), Term.of("freelancer", 2)
        ));

        // ---- Retail --------------------------------------------------------
        // Shops that sell goods without a stronger vertical fit above.
        // Weights are lower because most businesses use these words.
        map.put("retail", List.of(
                Term.of("retail", 3), Term.of("store", 1), Term.of("shop", 1),
                Term.of("boutique", 1), Term.of("merchandise", 2), Term.of("e-commerce", 3),
                Term.of("supermarket", 3), Term.of("mini-mart", 3), Term.of("kiosk", 3),
                Term.of("provisions", 3)
        ));

        // ---- Real Estate ---------------------------------------------------
        // Estate agents, property developers, property management companies.
        // Nigerian-specific vocabulary — C of O + Governor's consent +
        // NIS docs — signal harder than generic "property".
        map.put("real-estate", List.of(
                Term.of("real estate", 3), Term.of("realtor", 3),
                Term.of("property", 2), Term.of("properties", 2),
                Term.of("estate agent", 3), Term.of("landlord", 3),
                Term.of("rental", 2), Term.of("rentals", 3),
                Term.of("apartment", 2), Term.of("duplex", 3),
                Term.of("bungalow", 3), Term.of("terrace", 2),
                Term.of("self-con", 3), Term.of("self-contain", 3),
                Term.of("plot", 2), Term.of("acre", 2),
                Term.of("survey plan", 3), Term.of("c of o", 3),
                Term.of("certificate of occupancy", 3),
                Term.of("deed of assignment", 3),
                Term.of("governor's consent", 3),
                Term.of("gazette", 2), Term.of("tenant", 2),
                Term.of("lease", 2), Term.of("mortgage", 2),
                Term.of("bedroom", 1), Term.of("bathroom", 1),
                Term.of("furnished", 2), Term.of("serviced", 2)
        ));

        return map;
    }

    /** Score every vertical against the description; return the top-K matches
     *  (score &gt; 0), sorted highest first. Empty list when nothing matched. */
    public List<Match> topMatches(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        List<Match> ranked = new ArrayList<>();
        for (Map.Entry<String, List<Term>> entry : KEYWORDS.entrySet()) {
            int score = scoreFor(description, entry.getValue());
            if (score > 0) {
                ranked.add(new Match(entry.getKey(), score));
            }
        }
        ranked.sort(Comparator.comparingInt(Match::score).reversed());
        return ranked.size() <= TOP_K ? ranked : ranked.subList(0, TOP_K);
    }

    private static int scoreFor(String description, List<Term> terms) {
        int total = 0;
        for (Term t : terms) {
            if (t.pattern.matcher(description).find()) {
                total += t.weight;
            }
        }
        return total;
    }

    /** One vertical id + its cumulative keyword score for this description. */
    public record Match(String verticalId, int score) {
    }
}
