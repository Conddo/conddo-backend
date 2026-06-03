package io.conddo.studio.web.dto;

import java.util.Map;

/**
 * Admin patch payload for {@code PATCH /api/jobs/admin/design-standards/{id}}.
 * Every field is optional — null = no-op (PATCH semantics). {@code kind} is
 * immutable to keep the AI prompt-injection contract stable; rename via name +
 * description instead.
 */
public record UpdateDesignStandardRequest(
        String vertical,
        String name,
        String description,
        Map<String, Object> content,
        Boolean active) {
}
