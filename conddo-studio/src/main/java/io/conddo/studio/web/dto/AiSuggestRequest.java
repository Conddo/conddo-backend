package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Request AI copy for a website section (§8), e.g. {@code section = "HERO"}. */
public record AiSuggestRequest(@NotBlank String section) {
}
