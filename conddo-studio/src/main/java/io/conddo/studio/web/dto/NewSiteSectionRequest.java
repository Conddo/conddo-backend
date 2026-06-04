package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Add a new section to a page (§21.3). {@code type} must be one of the seven
 * catalogue types (§21.4); {@code content} JSONB is validated server-side
 * against the type's required keys.
 */
public record NewSiteSectionRequest(@NotBlank String type, Map<String, Object> content, Integer order) {
}
