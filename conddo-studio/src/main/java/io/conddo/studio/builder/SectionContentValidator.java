package io.conddo.studio.builder;

import java.util.Map;

/**
 * Lightweight server-side check on section {@code content} JSONB shape (§21.4).
 * Verifies the type is known and the required top-level keys are present and
 * non-blank. Deep schema validation (e.g. that {@code GALLERY.images} is an
 * array of objects) stays in the FE — the server is just a backstop against
 * malformed payloads that would crash the render pipeline downstream.
 */
public final class SectionContentValidator {

    private SectionContentValidator() {
    }

    /** Throws {@link IllegalArgumentException} (→ 400 BAD_REQUEST) on a problem. */
    public static String validate(String rawType, Map<String, Object> content) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("section type is required");
        }
        String type = rawType.trim().toUpperCase();
        if (!SectionTypes.ALL.contains(type)) {
            throw new IllegalArgumentException(
                    "Unknown section type: " + rawType + " (allowed: " + SectionTypes.ALL + ")");
        }
        for (String required : SectionTypes.REQUIRED_KEYS.getOrDefault(type, java.util.List.of())) {
            Object value = content == null ? null : content.get(required);
            if (value == null
                    || (value instanceof String s && s.isBlank())
                    || (value instanceof java.util.Collection<?> c && c.isEmpty())
                    || (value instanceof Map<?, ?> m && m.isEmpty())) {
                throw new IllegalArgumentException(
                        type + " content is missing required key: " + required);
            }
        }
        return type;
    }
}
