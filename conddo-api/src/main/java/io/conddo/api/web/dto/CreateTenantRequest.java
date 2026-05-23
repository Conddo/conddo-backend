package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateTenantRequest(
        @NotBlank String name,
        @NotBlank
        @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,48}[a-z0-9])$",
                message = "slug must be lowercase letters, digits and hyphens")
        String slug,
        String verticalId,
        String planId
) {
}
