package io.conddo.core.registry;

import io.conddo.core.vertical.VerticalConfig.MeasurementField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads vertical definitions from
 * {@code classpath*:verticals/*.yml} at startup. Adding a vertical
 * is dropping a YAML file under {@code resources/verticals/}; no
 * Java edit required.
 *
 * <p>Both {@link VerticalToolMatrix} and {@code VerticalConfigRegistry}
 * read their state from the map this component exposes — single
 * source of truth, no risk of drift between the JWT
 * {@code activeModules} claim and the dashboard config endpoint.
 */
@Component
public class VerticalDataLoader {

    private static final Logger log = LoggerFactory.getLogger(VerticalDataLoader.class);

    private final Map<String, VerticalDefinition> byId;

    public VerticalDataLoader() {
        this.byId = loadAll();
        log.info("Loaded {} vertical definitions from YAML: {}",
                byId.size(), byId.keySet());
    }

    /**
     * The vertical-id → definition map, including alias entries
     * (e.g. both {@code "general"} and {@code "default"} resolve to
     * the same definition).
     */
    public Map<String, VerticalDefinition> all() {
        return byId;
    }

    // ----- loader -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, VerticalDefinition> loadAll() {
        Map<String, VerticalDefinition> out = new LinkedHashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:verticals/*.yml");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan verticals/ for YAML files", ex);
        }
        if (resources.length == 0) {
            throw new IllegalStateException(
                    "No vertical YAMLs found on classpath under verticals/ — "
                            + "the platform cannot resolve tool matrices without at least one");
        }
        Yaml yaml = new Yaml();
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> raw = yaml.load(is);
                VerticalDefinition def = parse(raw, resource.getFilename());
                out.put(def.id().toLowerCase(), def);
                for (String alias : def.aliases()) {
                    out.put(alias.toLowerCase(), def);
                }
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Failed to read vertical YAML: " + resource.getFilename(), ex);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static VerticalDefinition parse(Map<String, Object> raw, String filename) {
        if (raw == null) {
            throw new IllegalStateException("Empty vertical YAML: " + filename);
        }
        String id = required(raw, "id", filename);
        String name = required(raw, "name", filename);
        Map<String, Object> tools = (Map<String, Object>) raw.getOrDefault("tools", Map.of());
        List<String> starter = (List<String>) tools.getOrDefault("starter", List.of());
        List<String> business = (List<String>) tools.getOrDefault("business", List.of());
        List<String> pro = (List<String>) tools.getOrDefault("pro", List.of());
        List<String> stages = (List<String>) raw.getOrDefault("orderStages", List.of());
        List<String> sections = (List<String>) raw.getOrDefault("websiteSections", List.of());
        List<String> aliases = (List<String>) raw.getOrDefault("aliases", List.of());
        List<MeasurementField> measurements = parseMeasurements(
                (List<Map<String, Object>>) raw.getOrDefault("measurementFields", List.of()));
        return new VerticalDefinition(id, name, starter, business, pro, stages,
                measurements, sections, aliases);
    }

    private static List<MeasurementField> parseMeasurements(List<Map<String, Object>> raw) {
        List<MeasurementField> out = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            out.add(new MeasurementField(
                    String.valueOf(entry.get("key")),
                    String.valueOf(entry.get("label")),
                    String.valueOf(entry.getOrDefault("unit", "in"))));
        }
        return out;
    }

    private static String required(Map<String, Object> raw, String key, String filename) {
        Object value = raw.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException(
                    "Vertical YAML " + filename + " missing required field: " + key);
        }
        return String.valueOf(value);
    }
}
