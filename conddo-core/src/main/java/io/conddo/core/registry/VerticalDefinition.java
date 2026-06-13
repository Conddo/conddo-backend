package io.conddo.core.registry;

import io.conddo.core.vertical.VerticalConfig.MeasurementField;

import java.util.List;

/**
 * Unified data shape for a vertical — both the tool matrix
 * ({@link VerticalToolMatrix}) and the FE config registry
 * ({@code VerticalConfigRegistry}) pull what they need from one
 * source. Loaded from {@code classpath:verticals/<id>.yml} by
 * {@link VerticalDataLoader}; adding a new vertical is dropping a
 * YAML file, no code edit needed.
 *
 * @param id                 stable id used in the JWT vertical claim
 * @param name               display name surfaced in the dashboard
 * @param starterTools       tools available at the starter tier
 * @param businessToolsAdd   additions on top of starter at the business tier
 * @param proToolsAdd        additions on top of business at the pro tier
 * @param orderStages        default order-pipeline stage labels
 * @param measurementFields  measurements a tenant's CRM tracks (e.g. fashion)
 * @param websiteSections    section types the tenant's website supports
 * @param aliases            extra ids the loader maps to this entry (e.g. "default" → "general")
 */
public record VerticalDefinition(
        String id,
        String name,
        List<String> starterTools,
        List<String> businessToolsAdd,
        List<String> proToolsAdd,
        List<String> orderStages,
        List<MeasurementField> measurementFields,
        List<String> websiteSections,
        List<String> aliases
) {

    public VerticalDefinition {
        starterTools = starterTools == null ? List.of() : List.copyOf(starterTools);
        businessToolsAdd = businessToolsAdd == null ? List.of() : List.copyOf(businessToolsAdd);
        proToolsAdd = proToolsAdd == null ? List.of() : List.copyOf(proToolsAdd);
        orderStages = orderStages == null ? List.of() : List.copyOf(orderStages);
        measurementFields = measurementFields == null ? List.of() : List.copyOf(measurementFields);
        websiteSections = websiteSections == null ? List.of() : List.copyOf(websiteSections);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
