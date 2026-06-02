package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Request an AI colour palette from a primary hex colour (§8), e.g. {@code "#7C5CBF"}. */
public record PaletteRequest(@NotBlank String primaryHex) {
}
