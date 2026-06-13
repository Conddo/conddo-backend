package io.conddo.core.vertical;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.registry.VerticalDataLoader;
import io.conddo.core.registry.VerticalDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory registry of {@link VerticalConfig}s, keyed by vertical id.
 * Backs {@code GET /api/v1/verticals/{id}/config} with the reference
 * order stages, measurement fields, and website sections a vertical's
 * dashboard uses.
 *
 * <p>Data is loaded from {@code classpath:verticals/*.yml} by
 * {@link VerticalDataLoader} — same source of truth as
 * {@code VerticalToolMatrix}, so the dashboard and the JWT
 * {@code activeModules} claim can't drift apart.
 */
@Component
public class VerticalConfigRegistry {

    private final Map<String, VerticalConfig> byId;

    public VerticalConfigRegistry(VerticalDataLoader loader) {
        Map<String, VerticalConfig> built = new LinkedHashMap<>();
        for (Map.Entry<String, VerticalDefinition> e : loader.all().entrySet()) {
            VerticalDefinition def = e.getValue();
            built.put(e.getKey(), new VerticalConfig(
                    def.id(),
                    def.name(),
                    def.orderStages(),
                    def.measurementFields(),
                    def.websiteSections()));
        }
        this.byId = Map.copyOf(built);
    }

    /** The config for a vertical, or null if unknown. */
    public VerticalConfig find(String id) {
        return id == null ? null : byId.get(id.toLowerCase());
    }

    /** The config for a vertical, or 404 if unknown. */
    public VerticalConfig require(String id) {
        VerticalConfig config = find(id);
        if (config == null) {
            throw new NotFoundException("Unknown vertical: " + id);
        }
        return config;
    }
}
