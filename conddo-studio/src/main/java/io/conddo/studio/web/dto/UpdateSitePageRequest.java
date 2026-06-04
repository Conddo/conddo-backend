package io.conddo.studio.web.dto;

/** Partial page update (§21.3). All fields optional — null means leave alone. */
public record UpdateSitePageRequest(String slug, String title, Integer order, Boolean home) {
}
