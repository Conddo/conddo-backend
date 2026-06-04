package io.conddo.studio.web.dto;

import java.util.Map;

/**
 * Partial theme patch for {@code PATCH /api/jobs/:id/site/theme} (§21.3).
 * Body is a free-form map merged into the existing theme; null keys are
 * ignored.
 */
public record PatchSiteThemeRequest(Map<String, Object> theme) {
}
