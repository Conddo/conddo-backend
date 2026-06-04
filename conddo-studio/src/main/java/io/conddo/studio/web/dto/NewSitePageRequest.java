package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Add a new page to a site (§21.3). {@code home} optional — defaults to false. */
public record NewSitePageRequest(@NotBlank String slug, @NotBlank String title,
                                 Boolean home, Integer order) {
}
