package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rename an inventory category (HANDOFF_2026-06-09 §2.1). 80-char
 * ceiling matches the FE's input maxLength so a name that's too long
 * is rejected here before the service layer.
 */
public record UpdateCategoryRequest(@NotBlank @Size(max = 80) String name) {
}
