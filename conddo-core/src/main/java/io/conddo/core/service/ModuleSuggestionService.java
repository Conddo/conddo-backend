package io.conddo.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.ai.AnthropicGateway;
import io.conddo.core.registry.ModuleCatalogue;
import io.conddo.core.registry.VerticalDataLoader;
import io.conddo.core.registry.VerticalDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI-driven module recommendation (Vertical Inference Phase C).
 * Takes a free-text business description (and optional vertical
 * hint), asks Anthropic Claude to score each module 0–1 on
 * relevance, and returns the ranked list so the FE can preselect
 * an opt-in set during onboarding.
 *
 * <p>The vertical hint biases the prompt — Claude is told the
 * tenant identifies as that vertical so its scoring stays
 * anchored. The hint is optional; an unrecognized vertical just
 * drops the hint line.
 */
@Service
public class ModuleSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(ModuleSuggestionService.class);

    private final AnthropicGateway anthropic;
    private final VerticalDataLoader verticals;
    private final ObjectMapper objectMapper;

    public ModuleSuggestionService(AnthropicGateway anthropic, VerticalDataLoader verticals,
                                    ObjectMapper objectMapper) {
        this.anthropic = anthropic;
        this.verticals = verticals;
        this.objectMapper = objectMapper;
    }

    public Result suggest(String description, String verticalHint) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("businessDescription is required");
        }
        Set<String> moduleIds = allKnownModuleIds();
        String prompt = buildPrompt(description, verticalHint, moduleIds);
        String raw = anthropic.chatText(prompt);
        List<Score> scores = parseScores(raw, moduleIds);
        return new Result(scores);
    }

    // ----- internals --------------------------------------------------------

    private Set<String> allKnownModuleIds() {
        Set<String> sortedIds = new java.util.TreeSet<>();
        for (VerticalDefinition def : verticals.all().values()) {
            sortedIds.addAll(def.starterTools());
            sortedIds.addAll(def.businessToolsAdd());
            sortedIds.addAll(def.proToolsAdd());
        }
        return sortedIds;
    }

    private String buildPrompt(String description, String verticalHint, Set<String> moduleIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are helping classify a business onto Conddo, a software platform ")
                .append("that provides modular capability tools to small/medium businesses.\n\n");
        sb.append("Business description: ").append('"').append(description.trim()).append('"').append("\n\n");
        if (verticalHint != null && !verticalHint.isBlank() && verticals.all().containsKey(verticalHint.toLowerCase())) {
            VerticalDefinition v = verticals.all().get(verticalHint.toLowerCase());
            sb.append("The tenant self-identifies their vertical as: ").append(v.name())
                    .append(" (id=").append(v.id()).append("). Use this as a strong prior.\n\n");
        }
        sb.append("Below is the catalogue of every capability module available. For each module, ")
                .append("score 0.0–1.0 confidence that this business would use it. Higher = more likely.\n\n");
        sb.append("Modules:\n");
        for (String id : moduleIds) {
            sb.append("- ").append(id).append(": ").append(ModuleCatalogue.describe(id)).append("\n");
        }
        sb.append("\nReturn JSON ONLY, no prose, in this exact shape:\n");
        sb.append("{\"scores\":[{\"id\":\"<module-id>\",\"confidence\":0.0,\"reason\":\"<one short sentence>\"}]}");
        sb.append("\nInclude an entry for every module above. Use only the module ids listed.");
        return sb.toString();
    }

    private List<Score> parseScores(String raw, Set<String> validIds) {
        try {
            String json = extractJsonObject(raw);
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("scores");
            List<Score> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode node : arr) {
                String id = node.path("id").asText("");
                if (id.isBlank() || !validIds.contains(id) || !seen.add(id)) {
                    continue;
                }
                double confidence = clamp(node.path("confidence").asDouble(0.0));
                String reason = node.path("reason").asText("");
                out.add(new Score(id, confidence, reason));
            }
            out.sort(Comparator.comparingDouble(Score::confidence).reversed());
            return out;
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("Failed to parse Anthropic suggestion response: {}", ex.getMessage());
            throw new SuggestionUnavailableException(
                    "AI returned a response we couldn't parse. Try again or adjust the description.");
        }
    }

    /** Pull the first {...} object out of the response — Claude sometimes wraps in prose. */
    private static String extractJsonObject(String raw) {
        if (raw == null) {
            throw new IllegalStateException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("no JSON object in response");
        }
        return raw.substring(start, end + 1);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    public record Score(String id, double confidence, String reason) {
    }

    public record Result(List<Score> scores) {

        public List<Score> recommended() {
            return scores.stream().filter(s -> s.confidence >= 0.6).toList();
        }
    }

    public static class SuggestionUnavailableException extends RuntimeException {
        public SuggestionUnavailableException(String message) {
            super(message);
        }
    }
}
