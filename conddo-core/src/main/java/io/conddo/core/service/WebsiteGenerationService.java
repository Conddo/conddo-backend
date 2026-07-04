package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.ai.AiModelSelector;
import io.conddo.core.ai.AnthropicGateway;
import io.conddo.core.credits.CreditActions;
import io.conddo.core.registry.VerticalDataLoader;
import io.conddo.core.registry.VerticalDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the initial content + theme for a tenant's managed website.
 *
 * <p>Called once at tenant activation (see {@code TenantActivationListener})
 * to seed {@code tenant_sites.draft_sections} + {@code tenant_sites.draft_theme}.
 * The output is a draft — the owner reviews + publishes from the dashboard.
 *
 * <p><b>No credit charge:</b> the initial pass is bundled with the one-time
 * AI provisioning charge already booked at tenant creation. Regenerations
 * from the dashboard go through the AiGateway with
 * {@link CreditActions#WEBSITE_GENERATION} and consume 10 credits each.
 *
 * <p><b>Never breaks signup:</b> LLM failures fall through to a rule-based
 * default site so the tenant always lands with something publishable.
 */
@Service
public class WebsiteGenerationService {

    private static final Logger log = LoggerFactory.getLogger(WebsiteGenerationService.class);

    private final AnthropicGateway llm;
    private final AiModelSelector modelSelector;
    private final VerticalDataLoader verticals;
    private final ObjectMapper objectMapper;

    public WebsiteGenerationService(AnthropicGateway llm,
                                    AiModelSelector modelSelector,
                                    VerticalDataLoader verticals,
                                    ObjectMapper objectMapper) {
        this.llm = llm;
        this.modelSelector = modelSelector;
        this.verticals = verticals;
        this.objectMapper = objectMapper;
    }

    /** Produce a draft-ready managed website for the tenant. Always returns
     *  something — LLM outages fall back to a vertical-defaulted stub. */
    public Generated generate(String businessName, String verticalId, String websiteVibe) {
        try {
            String prompt = buildPrompt(businessName, verticalId, websiteVibe);
            String raw = llm.chatText(prompt, modelSelector.modelFor(CreditActions.WEBSITE_GENERATION));
            return parseResponse(raw, businessName, verticalId);
        } catch (RuntimeException ex) {
            log.warn("Website generation LLM call failed for {}: {}; using rule-based fallback",
                    businessName, ex.getMessage());
            return fallback(businessName, verticalId);
        }
    }

    // ----- internals --------------------------------------------------------

    private String buildPrompt(String businessName, String verticalId, String websiteVibe) {
        VerticalDefinition v = verticalId != null ? verticals.all().get(verticalId.toLowerCase()) : null;
        String verticalLabel = v != null ? v.name() : "General Business";

        StringBuilder sb = new StringBuilder();
        sb.append("You are writing the first-draft website content for a Nigerian small-business owner. ")
                .append("Voice: warm, clear, human. Confident but not pushy. No filler adjectives.\n\n");
        sb.append("Business name: ").append(businessName).append("\n");
        sb.append("Vertical: ").append(verticalLabel).append("\n");
        if (websiteVibe != null && !websiteVibe.isBlank()) {
            sb.append("The owner described the vibe they want as: \"").append(websiteVibe.trim()).append("\"\n");
        } else {
            sb.append("No specific vibe provided — pick sensible defaults for this vertical.\n");
        }
        sb.append('\n');
        sb.append("Return JSON ONLY in this exact shape (no prose outside the object):\n");
        sb.append("{\n")
                .append("  \"hero\": { \"headline\": \"6-10 words\", \"subheadline\": \"1 sentence\", \"ctaLabel\": \"2-3 words\" },\n")
                .append("  \"about\": { \"title\": \"2-4 words\", \"body\": \"2-3 sentences\" },\n")
                .append("  \"services\": [ { \"name\": \"3-5 words\", \"description\": \"1 sentence\" }, ... 3 to 5 items ],\n")
                .append("  \"contact\": { \"title\": \"2-4 words\", \"note\": \"1 sentence\" },\n")
                .append("  \"theme\": { \"primaryColor\": \"#RRGGBB\", \"accentColor\": \"#RRGGBB\", ")
                .append("\"backgroundColor\": \"#RRGGBB\", \"textColor\": \"#RRGGBB\", ")
                .append("\"fontFamily\": \"Inter, system-ui, sans-serif\" }\n");
        sb.append("}\n\n");
        sb.append("Rules:\n");
        sb.append("- Use naira symbol (₦) not NGN when quoting prices.\n");
        sb.append("- Do not use 'we offer' 'best in the industry' or other empty superlatives.\n");
        sb.append("- Colors match the vibe: warm/trustworthy = greens+beiges; bold/colourful = magentas+yellows; minimal = grays+one accent.\n");
        sb.append("- Never mention Conddo, AI, or that this was generated.\n");
        sb.append("- If the vertical is Pharmacy, include disclaimers-of-tone like 'Ask our pharmacist' rather than medical claims.\n");
        return sb.toString();
    }

    private Generated parseResponse(String raw, String businessName, String verticalId) {
        try {
            String json = extractJsonObject(raw);
            JsonNode root = objectMapper.readTree(json);

            Map<String, Object> sections = new LinkedHashMap<>();
            sections.put("hero", copyNode(root, "hero"));
            sections.put("about", copyNode(root, "about"));
            sections.put("services", copyServices(root));
            sections.put("contact", copyNode(root, "contact"));

            Map<String, Object> theme = copyNode(root, "theme");
            if (theme.isEmpty()) {
                theme = defaultTheme();
            }

            return new Generated(sections, theme);
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("Website JSON parse failed: {}", ex.getMessage());
            return fallback(businessName, verticalId);
        }
    }

    private static Map<String, Object> copyNode(JsonNode root, String field) {
        JsonNode node = root.path(field);
        Map<String, Object> map = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText("")));
        }
        return map;
    }

    private static List<Map<String, Object>> copyServices(JsonNode root) {
        JsonNode arr = root.path("services");
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode row : arr) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", row.path("name").asText(""));
                item.put("description", row.path("description").asText(""));
                out.add(item);
            }
        }
        return out;
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) throw new IllegalStateException("empty response");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) throw new IllegalStateException("no JSON in response");
        return raw.substring(start, end + 1);
    }

    /** Vertical-defaulted stub — guarantees a publishable draft even if the
     *  LLM is unreachable at signup time. */
    private Generated fallback(String businessName, String verticalId) {
        String vertical = verticalId != null ? verticalId.toLowerCase() : "general";

        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("hero", Map.of(
                "headline", businessName,
                "subheadline", "Welcome. Take a look around.",
                "ctaLabel", "Get in touch"
        ));
        sections.put("about", Map.of(
                "title", "About us",
                "body", "We're " + businessName + ". Add a short paragraph here from your dashboard settings."
        ));
        sections.put("services", defaultServices(vertical));
        sections.put("contact", Map.of(
                "title", "Get in touch",
                "note", "Reach out and we'll get back to you."
        ));

        return new Generated(sections, defaultTheme());
    }

    private static List<Map<String, Object>> defaultServices(String vertical) {
        List<Map<String, Object>> out = new ArrayList<>();
        switch (vertical) {
            case "pharmacy" -> {
                out.add(Map.of("name", "Prescriptions",
                        "description", "We fill prescriptions from qualified doctors."));
                out.add(Map.of("name", "Over-the-counter",
                        "description", "Common pain relievers, cold + flu remedies, vitamins."));
                out.add(Map.of("name", "Ask our pharmacist",
                        "description", "Free health advice from our licensed team."));
            }
            case "fashion" -> {
                out.add(Map.of("name", "Ready-to-wear",
                        "description", "Curated pieces you can take home today."));
                out.add(Map.of("name", "Custom orders",
                        "description", "Made to your measurements and taste."));
                out.add(Map.of("name", "Consultations",
                        "description", "Book a session and we'll help you plan."));
            }
            case "food-and-beverage" -> {
                out.add(Map.of("name", "Signature dishes",
                        "description", "Our regulars' favourites."));
                out.add(Map.of("name", "Catering",
                        "description", "For events big and small."));
                out.add(Map.of("name", "Reservations",
                        "description", "Reserve a table for your next visit."));
            }
            case "beauty-and-wellness" -> {
                out.add(Map.of("name", "Hair", "description", "Styling, colour and treatments."));
                out.add(Map.of("name", "Skin", "description", "Facials and skincare consultations."));
                out.add(Map.of("name", "Book a session", "description", "Reserve your appointment online."));
            }
            case "music-studio" -> {
                out.add(Map.of("name", "Recording", "description", "Full-service studio sessions."));
                out.add(Map.of("name", "Mixing + mastering", "description", "Get your tracks release-ready."));
                out.add(Map.of("name", "Book time", "description", "Reserve studio hours."));
            }
            default -> {
                out.add(Map.of("name", "What we do",
                        "description", "Add your first service here from the dashboard."));
                out.add(Map.of("name", "How it works",
                        "description", "Explain how customers work with you."));
                out.add(Map.of("name", "Get started",
                        "description", "Invite them to reach out."));
            }
        }
        return out;
    }

    private static Map<String, Object> defaultTheme() {
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("primaryColor", "#5B4EE8");     // Conddo primary — safe fallback
        theme.put("accentColor", "#9F8CFF");
        theme.put("backgroundColor", "#0a0a0c");
        theme.put("textColor", "#FFFFFF");
        theme.put("fontFamily", "Inter, system-ui, sans-serif");
        return theme;
    }

    /** Output payload — pair of section tree + theme tokens, both ready to
     *  be stored on {@code tenant_sites.draft_sections/draft_theme}. */
    public record Generated(Map<String, Object> sections, Map<String, Object> theme) {
    }
}
