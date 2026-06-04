package io.conddo.studio.web.dto;

import java.util.Map;

/**
 * Patch a section's content / order (§21.3). Type is immutable here — to change
 * type, delete + create.
 */
public record UpdateSiteSectionRequest(Map<String, Object> content, Integer order) {
}
