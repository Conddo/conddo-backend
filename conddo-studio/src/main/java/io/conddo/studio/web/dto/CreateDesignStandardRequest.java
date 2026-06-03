package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Admin create payload for {@code POST /api/jobs/admin/design-standards}. */
public record CreateDesignStandardRequest(
        String vertical,           // null/blank → global
        @NotBlank String kind,     // PALETTE | LAYOUT | COPY_PATTERN | TYPOGRAPHY
        @NotBlank String name,
        String description,
        Map<String, Object> content) {
}
